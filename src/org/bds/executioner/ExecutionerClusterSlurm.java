package org.bds.executioner;

import java.util.List;
import java.util.regex.Pattern;

import org.bds.Config;
import org.bds.cluster.host.HostResources;
import org.bds.task.Task;
import org.bds.util.Timer;

/**
 * Execute tasks in an SLURM cluster.
 *
 * @author pcingola
 */
public class ExecutionerClusterSlurm extends ExecutionerCluster {

	// Define commands
	public static final String KILL_COMMAND[] = { "scancel" };
	public static final String POST_MORTEM_COMMAND[] = { "sacct", "--format=User,JobID,account,Timelimit,elapsed,ReqMem,MaxRss,ExitCode", "-j" };
	public static final String STAT_COMMAND[] = { "squeue", "-h", "-o", "%A" };
	public static final String RUN_COMMAND[] = { "sbatch", "--parsable", "--no-requeue" };

	public static final String PID_REGEX_DEFAULT = "(\\d+)";

	boolean timeInMins = true; // Typically SLURM timeouts are in minutes

	public ExecutionerClusterSlurm(Config config) {
		super(config);

		clusterRunCommand = RUN_COMMAND;
		clusterKillCommand = KILL_COMMAND;
		clusterStatCommand = STAT_COMMAND;
		clusterPostMortemInfoCommand = POST_MORTEM_COMMAND;

		// When running sbatch you get a line lie this:
		//
		//		$ sbatch x.sh
		// 		Submitted batch job 171984
		//
		//		$ sbatch --parsable x.sh
		// 		171984
		//
		// So, this is a pattern matcher to parse the PID
		pidRegexStr = config.getPidRegex(PID_REGEX_DEFAULT);
		pidRegex = Pattern.compile(pidRegexStr);
		if (debug) log("Using pidRegex '" + pidRegexStr + "'");
	}

	/**
	 * Add resource options to command line parameters
	 */
	@Override
	protected void addResources(Task task, List<String> args) {
		// Add resources request
		HostResources res = task.getResources();

		// Cpu
		if (res.getCpus() > 0) {
			args.add("--cpus-per-task");
			args.add("" + res.getCpus());
		}

		// Memory
		if (res.getMem() > 0) {
			long memInM = Math.max(res.getMem() / (1024 * 1024), 1);
			args.add("--mem=" + memInM + "M");
		}

		// Timeout
		int clusterTimeout = calcTimeOut(res);
		if (clusterTimeout > 0) { // Hard timeout
			args.add("-t");
			args.add(time(clusterTimeout));
		}

		// A particular queue was requested?
		String queue = task.getQueue();
		if (queue != null && !queue.isEmpty()) {
			args.add("-p");
			args.add(queue);
		}

		args.add("--output");
		args.add(clusterStdFile(task.getStdoutFile()));

		args.add("--error");
		args.add(clusterStdFile(task.getStderrFile()));

	}

	/**
	 * Represent a time according for 'sbatch' command line arguments
	 */
	protected String time(int secs) {
		if (timeInMins) return secs / 60 + "";
		return Timer.toHHMMSS(secs * 1000L);
	}
}
