package org.bds.lang.statement;

import org.antlr.v4.runtime.tree.ParseTree;
import org.bds.lang.BdsNode;
import org.bds.run.BdsThread;

/**
 * An "print" statement
 *
 * @author pcingola
 */
public class Println extends Print {

	private static final long serialVersionUID = 2059553580460692477L;

	public Println(BdsNode parent, ParseTree tree) {
		super(parent, tree);
	}

	/**
	 * Run the program
	 */
	@Override
	public void runStep(BdsThread bdsThread) {
		String msg = "";
		if (expr != null) {
			bdsThread.run(expr); // Evaluate expression to show
			if (bdsThread.isCheckpointRecover()) return;
			msg = bdsThread.popString();
		}

		if (bdsThread.isCheckpointRecover()) return;
		System.out.println(!msg.isEmpty() ? msg : "");
	}

	@Override
	public String toAsm() {
		return expr.toAsm() + "println\n";
	}
}
