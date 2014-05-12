package ca.mcgill.mcb.pcingola.bigDataScript.task;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import ca.mcgill.mcb.pcingola.bigDataScript.cluster.host.HostResources;
import ca.mcgill.mcb.pcingola.bigDataScript.lang.Type;
import ca.mcgill.mcb.pcingola.bigDataScript.lang.TypeList;
import ca.mcgill.mcb.pcingola.bigDataScript.serialize.BigDataScriptSerialize;
import ca.mcgill.mcb.pcingola.bigDataScript.serialize.BigDataScriptSerializer;
import ca.mcgill.mcb.pcingola.bigDataScript.util.Gpr;
import ca.mcgill.mcb.pcingola.bigDataScript.util.Timer;

/**
 * A task to be executed by an Executioner
 *
 * @author pcingola
 */
public class Task implements BigDataScriptSerialize {

	public enum DependencyState {
		OK // All dependencies have successfully finished execution
		, WAIT // Still waiting for a dependency to finish
		, ERROR // One or more dependency failed
	}

	public enum TaskState {
		NONE // Task created, nothing happened so far
		, STARTED // Process started (or queued for execution)
		, START_FAILED // Process failed to start (or failed to queue)
		, RUNNING // Running OK
		, ERROR // Filed while running
		, ERROR_TIMEOUT // Filed due to timeout
		, KILLED // Task was killed
		, FINISHED // Finished OK
		;

		public static TaskState exitCode2taskState(int exitCode) {
			switch (exitCode) {
			case EXITCODE_OK:
				return FINISHED;

			case EXITCODE_ERROR:
				return ERROR;

			case EXITCODE_TIMEOUT:
				return ERROR_TIMEOUT;

			case EXITCODE_KILLED:
				return KILLED;

			default:
				return ERROR;
			}

		}

		public boolean isError() {
			return (this == TaskState.START_FAILED) //
					|| (this == TaskState.ERROR) //
					|| (this == TaskState.ERROR_TIMEOUT) //
					|| (this == TaskState.KILLED) //
			;
		}

		public boolean isFinished() {
			return this == TaskState.FINISHED;
		}

		public boolean isRunning() {
			return this == TaskState.RUNNING;
		}

		public boolean isStarted() {
			return this == TaskState.STARTED;
		}
	}

	public static final int MAX_HINT_LEN = 150;

	// TODO: This should be a variable (SHEBANG?)
	public static final String SHE_BANG = "#!/bin/sh -e\n\n"; // Use '-e' so that shell script stops after first error

	// Exit codes (see bds.go)
	public static final int EXITCODE_OK = 0;
	public static final int EXITCODE_ERROR = 1;
	public static final int EXITCODE_TIMEOUT = 2;
	public static final int EXITCODE_KILLED = 3;

	protected boolean verbose, debug;
	protected boolean canFail; // Allow execution to fail
	protected int bdsLineNum; // Program's line number that created this task (used for reporting errors)
	protected int exitValue; // Exit (error) code
	protected int failCount, maxFailCount; // Number of times that this task failed
	protected String id; // Task ID
	protected String bdsFileName; // Program file that created this task (used for reporting errors)
	protected String pid; // PID (if any)
	protected String programFileDir; // Program file's dir
	protected String programFileName; // Program file name
	protected String programTxt; // Program's text (program's code)
	protected String node; // Preferred execution node (or hostname)
	protected String queue; // Preferred execution queue
	protected String stdoutFile, stderrFile, exitCodeFile; // STDOUT, STDERR & exit code Files
	protected String errorMsg; // Error messages
	protected String postMortemInfo; // Error information about task that failed
	protected Date runningStartTime, runningEndTime;
	protected TaskState taskState;
	protected HostResources resources; // Resources to be consumes when executing this task
	protected List<String> inputFiles; // Input files generated by this task. TODO Serialize this!
	protected List<String> outputFiles; // Output files generated by this task. TODO Serialize this!
	protected String checkOutputFiles; // Errors that pop-up when checking output files
	protected List<Task> dependency; // Task that need to finish before this one is executed

	public Task() {
		this(null, null, null, null, -1);
	}

	public Task(String id) {
		this(id, null, null, null, -1);
	}

	public Task(String id, String programFileName, String programTxt, String bdsFileName, int bdsLineNum) {
		this.id = id;
		this.programFileName = programFileName;
		this.programTxt = programTxt;
		this.bdsFileName = bdsFileName;
		this.bdsLineNum = bdsLineNum;
		resources = new HostResources();
		reset();
	}

	/**
	 * Add a dependency task (i.e. taskDep must finish before this task starts)
	 * @param taskDep
	 */
	public void addDependency(Task taskDep) {
		if (dependency == null) dependency = new LinkedList<Task>();
		dependency.add(taskDep);
	}

	public boolean canRetry() {
		return failCount < maxFailCount;
	}

	/**
	 * Can this task run?
	 * I.e.: It has not been started yet and all dependencies are satisfied
	 * @return true if we are ready to run this task
	 */
	public boolean canRun() {
		return taskState == TaskState.NONE;
	}

	/**
	 * Check if output files are OK
	 * @return true if OK, false there is an error (output file does not exist or has zero length)
	 */
	public String checkOutputFiles() {
		if (checkOutputFiles != null) return checkOutputFiles;
		if (!isStateFinished() || outputFiles == null) return ""; // Nothing to check

		checkOutputFiles = "";
		for (String fileName : outputFiles) {
			File file = new File(fileName);
			if (!file.exists()) checkOutputFiles += "Error: Output file '" + fileName + "' does not exist.";
			else if (file.length() <= 0) checkOutputFiles += "Error: Output file '" + fileName + "' has zero length.";
		}

		if (verbose && !checkOutputFiles.isEmpty()) Timer.showStdErr(checkOutputFiles);
		return checkOutputFiles;
	}

	/**
	 * Create a program file
	 */
	public void createProgramFile() {
		if (debug) Gpr.debug("Saving file '" + programFileName + "'");

		// Create dir
		try {
			File dir = new File(programFileName);
			dir = dir.getCanonicalFile().getParentFile();
			if (dir != null) {
				dir.mkdirs();
				programFileDir = dir.getCanonicalPath();
			}
		} catch (IOException e) {
			// Nothing to do
		}

		// Create file
		Gpr.toFile(programFileName, SHE_BANG + programTxt);
		(new File(programFileName)).setExecutable(true); // Allow execution

		// Set default file names
		String base = Gpr.removeExt(programFileName);
		if (stdoutFile == null) stdoutFile = base + ".stdout";
		if (stderrFile == null) stderrFile = base + ".stderr";
		if (exitCodeFile == null) exitCodeFile = base + ".exitCode";
	}

	/**
	 * Remove tmp files on exit
	 */
	public void deleteOnExit() {
		if (programFileDir != null) (new File(programFileDir)).deleteOnExit(); // Files are deleted in reverse order. So dir has to be first to make sure it is empty when deleted (otherwise it will not be deleted)
		if (stdoutFile != null) (new File(stdoutFile)).deleteOnExit();
		if (stderrFile != null) (new File(stderrFile)).deleteOnExit();
		if (exitCodeFile != null) (new File(exitCodeFile)).deleteOnExit();
		if (programFileName != null) (new File(programFileName)).deleteOnExit();
	}

	/**
	 * Mark output files to be deleted on exit
	 */
	public void deleteOutputFilesOnExit() {
		if (outputFiles == null) return; // Nothing to check

		for (String fileName : outputFiles) {
			File file = new File(fileName);
			if (file.exists()) file.deleteOnExit();
		}
	}

	public DependencyState dependencyState() {
		HashSet<Task> tasks = new HashSet<Task>();
		return dependencyState(tasks);
	}

	/**
	 * Are dependencies satisfied? (i.e. can we execute this task?)
	 * @return true if all dependencies are satisfied
	 */
	protected synchronized DependencyState dependencyState(Set<Task> tasksVisited) {
		// Task already finished?
		if (isDone()) {
			if (isCanFail() || isDoneOk()) return DependencyState.OK;
			return DependencyState.ERROR;
		}
		if (isStarted()) return DependencyState.WAIT; // Already started but not finished? => Then you should wait;
		if (dependency == null) return DependencyState.OK; // No dependencies? => we are ready to execute

		// TODO: How do we deal with circular dependency
		if (tasksVisited.contains(this)) throw new RuntimeException("Circular dependency on task:" + this);
		tasksVisited.add(this);

		// Check that all dependencies are OK
		for (Task task : dependency) {
			// Analyze dependent task
			DependencyState dep = task.dependencyState(tasksVisited);
			if (dep != DependencyState.OK) return dep; // Propagate non-OK states (i.e. error or wait)
			if (!task.isDone()) return DependencyState.WAIT; // Dependency OK, but not finished? => Wait for it
		}

		// Only if all dependent tasks are OK, we can say that we are ready
		return DependencyState.OK;
	}

	/**
	 * Elapsed number of seconds this task has been executing
	 * @return
	 */
	public int elapsedSecs() {
		if (runningStartTime == null) return -1; // Not started?
		if (getResources() == null) return -1; // No resources?

		// Calculate elapsed processing time
		long end = (runningEndTime != null ? runningEndTime : new Date()).getTime();
		long start = runningStartTime.getTime();
		int elapsedSecs = (int) ((end - start) / 1000);
		return elapsedSecs;
	}

	public String getBdsFileName() {
		return bdsFileName;
	}

	public int getBdsLineNum() {
		return bdsLineNum;
	}

	public List<Task> getDependency() {
		return dependency;
	}

	public String getErrorMsg() {
		return errorMsg;
	}

	public String getExitCodeFile() {
		return exitCodeFile;
	}

	public synchronized int getExitValue() {
		if (!checkOutputFiles().isEmpty()) return 1; // Any output file failed?
		return exitValue;
	}

	public int getFailCount() {
		return failCount;
	}

	public String getId() {
		return id;
	}

	public List<String> getInputFiles() {
		return inputFiles;
	}

	public int getMaxFailCount() {
		return maxFailCount;
	}

	public String getName() {
		return Gpr.baseName(id);
	}

	public String getNode() {
		return node;
	}

	public List<String> getOutputFiles() {
		return outputFiles;
	}

	public synchronized String getPid() {
		return pid;
	}

	public String getPostMortemInfo() {
		return postMortemInfo;
	}

	public String getProgramFileName() {
		return programFileName;
	}

	/**
	 * A short text describing the task (extracted from program text)
	 * @return
	 */
	public String getProgramHint() {
		if (programTxt == null) return "";

		for (String line : programTxt.split("\n"))
			if (!(line.isEmpty() || line.startsWith("#"))) {
				String hint = line.length() > MAX_HINT_LEN ? line.substring(0, MAX_HINT_LEN) : line;
				hint = hint.replace('\'', ' ');
				hint = hint.replace('\t', ' ');
				return hint;
			}

		return "";
	}

	public String getProgramTxt() {
		return programTxt;
	}

	public String getQueue() {
		return queue;
	}

	public HostResources getResources() {
		return resources;
	}

	public Date getRunningEndTime() {
		return runningEndTime;
	}

	public Date getRunningStartTime() {
		return runningStartTime;
	}

	public String getStderrFile() {
		return stderrFile;
	}

	public String getStdoutFile() {
		return stdoutFile;
	}

	public TaskState getTaskState() {
		return taskState;
	}

	public boolean isCanFail() {
		return canFail;
	}

	/**
	 * Has this task finished? Either finished OK or finished because of errors.
	 * @return
	 */
	public synchronized boolean isDone() {
		return isStateError() || isStateFinished();
	}

	/**
	 * Has this task been executed successfully?
	 * The task has finished, exit code is zero and all output files have been created
	 *
	 * @return
	 */
	public synchronized boolean isDoneOk() {
		return isStateFinished() && (exitValue == 0) && checkOutputFiles().isEmpty();
	}

	/**
	 * Has this task been executed and failed?
	 *
	 * This is true if:
	 * 		- The task has finished execution and it is in an error state
	 * 		- OR exitValue is non-zero
	 * 		- OR any of the output files was not created
	 *
	 * @return
	 */
	public synchronized boolean isFailed() {
		return isStateError() || (exitValue != 0) || !checkOutputFiles().isEmpty();
	}

	/**
	 * Has the task been started?
	 * @return
	 */
	public boolean isStarted() {
		return taskState != TaskState.NONE;
	}

	/**
	 * Is this task in any error or killed state?
	 * @return
	 */
	public boolean isStateError() {
		return taskState.isError();
	}

	public boolean isStateFinished() {
		return taskState.isFinished();
	}

	public boolean isStateRunning() {
		return taskState.isRunning();
	}

	public synchronized boolean isStateStarted() {
		return taskState.isStarted();
	}

	/**
	 * Has this task run out of time?
	 * @return
	 */
	public boolean isTimedOut() {
		int elapsedSecs = elapsedSecs();
		if (elapsedSecs < 0) return false;

		// Run out of time?
		// Note: We use wall-timeout instead of timeout, because we don't really know
		//       how long the task is being executed (the cluster scheduler can have
		//       the task in a queue for a long time).
		int timeout = (int) getResources().getWallTimeout();
		return elapsedSecs > timeout;
	}

	/**
	 * Reset parameters and allow a task to be re-executed
	 */
	public void reset() {
		taskState = TaskState.NONE;
		exitValue = 0;
		runningStartTime = null;
		runningEndTime = null;
		postMortemInfo = null;
		errorMsg = null;
		checkOutputFiles = null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void serializeParse(BigDataScriptSerializer serializer) {
		// Note that "Task classname" field has been consumed at this point
		id = serializer.getNextField();
		bdsFileName = serializer.getNextFieldString();
		bdsLineNum = (int) serializer.getNextFieldInt();
		canFail = serializer.getNextFieldBool();
		taskState = TaskState.valueOf(serializer.getNextFieldString());
		exitValue = (int) serializer.getNextFieldInt();
		node = serializer.getNextFieldString();
		queue = serializer.getNextFieldString();
		programFileName = serializer.getNextFieldString();
		programTxt = serializer.getNextFieldString();
		stdoutFile = serializer.getNextFieldString();
		stderrFile = serializer.getNextFieldString();
		exitCodeFile = serializer.getNextFieldString();

		inputFiles = serializer.getNextFieldList(TypeList.get(Type.STRING));
		outputFiles = serializer.getNextFieldList(TypeList.get(Type.STRING));

		resources = new HostResources();
		resources.serializeParse(serializer);
	}

	@Override
	public String serializeSave(BigDataScriptSerializer serializer) {
		return getClass().getSimpleName() //
				+ "\t" + id //
				+ "\t" + serializer.serializeSaveValue(bdsFileName) //
				+ "\t" + bdsLineNum //
				+ "\t" + canFail //
				+ "\t" + serializer.serializeSaveValue(taskState.toString()) //
				+ "\t" + exitValue //
				+ "\t" + serializer.serializeSaveValue(node) //
				+ "\t" + serializer.serializeSaveValue(queue) //
				+ "\t" + serializer.serializeSaveValue(programFileName) //
				+ "\t" + serializer.serializeSaveValue(programTxt) //
				+ "\t" + serializer.serializeSaveValue(stdoutFile) //
				+ "\t" + serializer.serializeSaveValue(stderrFile) //
				+ "\t" + serializer.serializeSaveValue(exitCodeFile) //
				+ "\t" + serializer.serializeSaveValue(inputFiles) //
				+ "\t" + serializer.serializeSaveValue(outputFiles) //
				+ "\t" + resources.serializeSave(serializer) //
				+ "\n";
	}

	public void setCanFail(boolean canFail) {
		this.canFail = canFail;
	}

	public void setDebug(boolean debug) {
		this.debug = debug;
	}

	public void setErrorMsg(String errorMsg) {
		this.errorMsg = errorMsg;
	}

	public synchronized void setExitValue(int exitValue) {
		this.exitValue = exitValue;
	}

	public void setInputFiles(List<String> inputFiles) {
		if (inputFiles == null) this.inputFiles = inputFiles;
		else {
			this.inputFiles = new ArrayList<String>();
			this.inputFiles.addAll(inputFiles);
		}
	}

	public void setMaxFailCount(int maxFailCount) {
		this.maxFailCount = maxFailCount;
	}

	public void setNode(String node) {
		this.node = node;
	}

	public void setOutputFiles(List<String> outputFiles) {
		if (outputFiles == null) this.outputFiles = outputFiles;
		else {
			this.outputFiles = new ArrayList<String>();
			this.outputFiles.addAll(outputFiles);
		}
	}

	public void setPid(String pid) {
		this.pid = pid;
	}

	public void setPostMortemInfo(String postMortemInfo) {
		this.postMortemInfo = postMortemInfo;
	}

	public void setQueue(String queue) {
		this.queue = queue;
	}

	private void setState(TaskState taskState) {
		this.taskState = taskState;
	}

	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}

	/**
	 * Change state: Make sure state changes are valid
	 * @param newState
	 */
	public synchronized void state(TaskState newState) {
		if (newState == null) throw new RuntimeException("Cannot change to 'null' state.\n" + this);
		if (newState == taskState) return; // Nothing to do

		switch (newState) {
		case STARTED:
			if (taskState == TaskState.NONE) setState(newState);
			else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		case START_FAILED:
			if (taskState == TaskState.NONE) {
				setState(newState);
				runningStartTime = runningEndTime = new Date();
				failCount++;
			} else if (taskState == TaskState.KILLED) ; // OK, don't change state
			else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		case RUNNING:
			if (taskState == TaskState.STARTED) {
				setState(newState);
				runningStartTime = new Date();
			} else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		case ERROR:
		case ERROR_TIMEOUT:
			failCount++; // Count failed, proceed to 'FINISHED' state

		case FINISHED:
			if (taskState == TaskState.RUNNING) {
				setState(newState);
				runningEndTime = new Date();
			} else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		case KILLED:
			if ((taskState == TaskState.RUNNING) // A task can be killed while running...
					|| (taskState == TaskState.STARTED) // or right after it started
					|| (taskState == TaskState.NONE) // or even if it was not started
			) {
				setState(newState);
				runningEndTime = new Date();
				failCount++;
			} else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		default:
			// Ignore other state changes
			throw new RuntimeException("Unimplemented state: '" + newState + "'");
		}

		// Finished OK? Check that output files are OK as well
		if (isStateFinished()) {
			// Update failCount if output files failed to be created
			if (!isCanFail() && !checkOutputFiles().isEmpty()) failCount++;
		}
	}

	@Override
	public String toString() {
		return toString(verbose, debug);
	}

	public String toString(boolean verbose) {
		return toString(verbose, debug);
	}

	public String toString(boolean verbose, boolean showCode) {
		StringBuilder sb = new StringBuilder();

		if (verbose) {
			sb.append("\tProgram & line     : '" + bdsFileName + "', line " + bdsLineNum + "\n");
			sb.append("\tTask ID            : '" + id + "'\n");
			sb.append("\tTask PID           : '" + pid + "'\n");
			sb.append("\tTask hint          : '" + getProgramHint() + "'\n");
			sb.append("\tTask resources     : '" + getResources() + "'\n");
			sb.append("\tState              : '" + taskState + "'\n");
			sb.append("\tDependency state   : '" + dependencyState() + "'\n");
			sb.append("\tRetries available  : '" + failCount + "'\n");
			sb.append("\tInput files        : '" + inputFiles + "'\n");
			sb.append("\tOutput files       : '" + outputFiles + "'\n");

			if (dependency != null && !dependency.isEmpty()) {
				sb.append("\tTask dependencies  : ");
				sb.append(" [ ");
				boolean comma = false;
				for (Task t : dependency) {
					sb.append((comma ? ", " : "") + "'" + t.getId() + "'");
					comma = true;
				}
				sb.append(" ]\n");
			}

			sb.append("\tScript file        : '" + programFileName + "'\n");
			if (errorMsg != null) sb.append("\tError message      : '" + errorMsg + "'\n");
			sb.append("\tExit status        : '" + exitValue + "'\n");

			String ch = checkOutputFiles();
			if ((ch != null) && !ch.isEmpty()) sb.append("\tOutput file checks : '" + ch + "'");

			// Show code?
			if (showCode && (getProgramTxt() != null) && !getProgramTxt().isEmpty()) sb.append("\tProgram            : \n" + Gpr.prependEachLine("\t\t", getProgramTxt()));

			// Show StdErr
			String tailErr = TailFile.tail(stderrFile);
			if ((tailErr != null) && !tailErr.isEmpty()) sb.append("\tStdErr (10 lines)  :\n" + Gpr.prependEachLine("\t\t", tailErr));

			// Show StdOut
			String tailOut = TailFile.tail(stdoutFile);
			if ((tailOut != null) && !tailOut.isEmpty()) sb.append("\tStdOut (10 lines)  :\n" + Gpr.prependEachLine("\t\t", tailOut));
		} else sb.append("'" + bdsFileName + "', line " + bdsLineNum);

		return sb.toString();
	}
}
