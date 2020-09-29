package org.bds.lang.nativeFunctions;

import org.bds.lang.Parameters;
import org.bds.lang.type.Type;
import org.bds.lang.type.Types;
import org.bds.lang.value.Value;
import org.bds.run.BdsThread;

/**
 * Get an environment variable, or empty string if it doesn't exist
 *
 * @author pcingola
 */
public class FunctionNativeGetVar extends FunctionNative {

	private static final long serialVersionUID = 6415943745404236449L;

	public FunctionNativeGetVar() {
		super();
	}

	@Override
	protected void initFunction() {
		functionName = "getVar";
		returnType = Types.STRING;

		String argNames[] = { "name" };
		Type argTypes[] = { Types.STRING };
		parameters = Parameters.get(argTypes, argNames);
		addNativeFunction();
	}

	@Override
	protected Object runFunctionNative(BdsThread bdsThread) {
		String name = bdsThread.getString("name");
		Value val = bdsThread.getValue(name);
		if (val == null) return "";
		return val.asString();
	}

}
