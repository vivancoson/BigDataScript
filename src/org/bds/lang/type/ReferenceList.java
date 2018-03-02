package org.bds.lang.type;

import org.antlr.v4.runtime.tree.ParseTree;
import org.bds.compile.CompilerMessage.MessageType;
import org.bds.compile.CompilerMessages;
import org.bds.lang.BdsNode;
import org.bds.lang.expression.Expression;
import org.bds.lang.value.LiteralInt;
import org.bds.lang.value.Value;
import org.bds.lang.value.ValueList;
import org.bds.run.BdsThread;
import org.bds.scope.Scope;
import org.bds.scope.ScopeSymbol;
import org.bds.util.Gpr;

/**
 * A reference to a list/array expression.
 * E.g. list[3]
 *
 * @author pcingola
 */
public class ReferenceList extends Reference {

	protected Expression exprList; // An arbitrary expression that returns a list
	protected Expression exprIdx; // An arbitrary expression that returns an int

	public ReferenceList(BdsNode parent, ParseTree tree) {
		super(parent, tree);
	}

	public ValueList getList(Scope scope) {
		ScopeSymbol ss = getScopeSymbol(scope);
		if (ss == null) return null;
		return (ValueList) ss.getValue();
	}

	/**
	 * Get symbol from scope
	 */
	@Override
	public ScopeSymbol getScopeSymbol(Scope scope) {
		if (exprList instanceof ReferenceVar) return ((ReferenceVar) exprList).getScopeSymbol(scope);
		return null;
	}

	public Type getType(Scope scope) {
		ScopeSymbol ss = getScopeSymbol(scope);
		return ss.getType();
	}

	@Override
	public String getVariableName() {
		if (exprList instanceof ReferenceVar) return ((ReferenceVar) exprList).getVariableName();
		return null;
	}

	@Override
	public boolean isReturnTypesNotNull() {
		return returnType != null;
	}

	public boolean isVariableReference() {
		return exprList instanceof ReferenceVar;
	}

	@Override
	protected void parse(ParseTree tree) {
		exprList = (Expression) factory(tree, 0);
		// child[1] = '['
		exprIdx = (Expression) factory(tree, 2);
		// child[3] = ']'
	}

	@Override
	public void parse(String str) {
		int idx1 = str.indexOf('[');
		int idx2 = str.lastIndexOf(']');
		if ((idx1 <= 0) || (idx2 <= idx1)) throw new RuntimeException("Cannot parse list reference '" + str + "'");

		// Create VarReference
		String varName = str.substring(0, idx1);
		ReferenceVar refVar = new ReferenceVar(this, null);
		refVar.parse(varName);
		exprList = refVar;

		// Create index expression
		String idxStr = str.substring(idx1 + 1, idx2);

		if (idxStr.startsWith("$")) {
			// We have to interpolate this string
			exprIdx = ReferenceVar.factory(this, idxStr.substring(1));
		} else {
			// String literal
			LiteralInt litInt = new LiteralInt(this, null);
			litInt.setValue(Gpr.parseLongSafe(idxStr));
			exprIdx = litInt;
		}
	}

	@Override
	public Type returnType(Scope scope) {
		if (returnType != null) return returnType;

		exprIdx.returnType(scope);
		Type nameType = exprList.returnType(scope);

		if (nameType == null) return null;
		if (nameType.isList()) returnType = ((TypeList) nameType).getElementType();

		return returnType;
	}

	/**
	 * Evaluate an expression
	 */
	@Override
	public void runStep(BdsThread bdsThread) {
		// Evaluate expressions
		bdsThread.run(exprList);
		bdsThread.run(exprIdx);

		if (bdsThread.isCheckpointRecover()) return;

		// Get results
		long idx = bdsThread.popInt();
		ValueList vlist = (ValueList) bdsThread.pop();
		if (vlist.isIndexOutOfRange(idx)) throw new RuntimeException("Trying to access element number " + idx + " from list '" + getVariableName() + "' (list size: " + vlist.size() + ").");

		// Push value to stack
		bdsThread.push(vlist.getValue(idx));
	}

	@Override
	public void setValue(BdsThread bdsThread, Value value) {
		if (value == null) return;

		bdsThread.run(exprIdx);
		int idx = (int) bdsThread.popInt();
		if (bdsThread.isCheckpointRecover()) return;

		ValueList vlist = getList(bdsThread.getScope());
		if (vlist == null) bdsThread.fatalError(this, "Cannot assign to non-variable '" + this + "'");
		vlist.setValue(idx, value);
	}

	@Override
	public String toString() {
		return exprList + "[" + exprIdx + "]";
	}

	@Override
	public void typeCheck(Scope scope, CompilerMessages compilerMessages) {
		// Calculate return type
		returnType(scope);

		if ((exprList.getReturnType() != null) && !exprList.getReturnType().isList()) compilerMessages.add(this, "Expression '" + exprList + "' is not a list/array", MessageType.ERROR);
		if (exprIdx != null) exprIdx.checkCanCastToInt(compilerMessages);
	}

	@Override
	protected void typeCheckNotNull(Scope scope, CompilerMessages compilerMessages) {
		throw new RuntimeException("This method should never be called!");
	}

}
