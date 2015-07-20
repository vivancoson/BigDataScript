package ca.mcgill.mcb.pcingola.bigDataScript.lang.nativeMethods.string;

import ca.mcgill.mcb.pcingola.bigDataScript.data.Data;
import ca.mcgill.mcb.pcingola.bigDataScript.lang.Parameters;
import ca.mcgill.mcb.pcingola.bigDataScript.lang.Type;
import ca.mcgill.mcb.pcingola.bigDataScript.lang.nativeMethods.MethodNative;
import ca.mcgill.mcb.pcingola.bigDataScript.run.BdsThread;
import ca.mcgill.mcb.pcingola.bigDataScript.util.Gpr;

public class MethodNative_string_write_str extends MethodNative {
	public MethodNative_string_write_str() {
		super();
	}

	@Override
	protected void initMethod() {
		functionName = "write";
		classType = Type.STRING;
		returnType = Type.STRING;

		String argNames[] = { "this", "str" };
		Type argTypes[] = { Type.STRING, Type.STRING };
		parameters = Parameters.get(argTypes, argNames);
		addNativeMethodToClassScope();
	}

	@Override
	protected Object runMethodNative(BdsThread bdsThread, Object objThis) {
		// Download data if nescesary
		Data data = bdsThread.data(objThis.toString());

		// Save to file
		String str = bdsThread.getString("str");
		if (data.isRemote()) {
			// Save to temp file and upload
			String tmpFileName = "";
			Gpr.toFile(tmpFileName, str);

			if (!data.upload(tmpFileName)) return ""; // Failed upload?
		} else {
			// Save to local file
			Gpr.toFile(data.getLocalPath(), str);
		}

		// OK
		return str;
	}
}
