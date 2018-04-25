package org.bds.task;

import org.bds.run.BdsThread;

public enum TaskState {
	NONE // Task created, nothing happened so far
	, SCHEDULED // Process is scheduled to start
	, STARTED // Process started (or queued for execution)
	, START_FAILED // Process failed to start (or failed to queue)
	, RUNNING // Running OK
	, ERROR // Filed while running
	, ERROR_TIMEOUT // Filed due to timeout
	, KILLED // Task was killed
	, FINISHED // Finished OK
	;

	public static TaskState exitCode2taskState(int exitCode) {
		//		Gpr.debug("exitCode: " + exitCode + "\t" + (exitCode & 0x007f));
		switch (exitCode) {
		case BdsThread.EXITCODE_OK:
			return FINISHED;

		case BdsThread.EXITCODE_ERROR:
			return ERROR;

		case BdsThread.EXITCODE_TIMEOUT:
			return ERROR_TIMEOUT;

		case BdsThread.EXITCODE_KILLED:
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
