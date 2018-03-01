package org.bds.lang.value;

import java.util.List;

import org.bds.lang.type.Type;
import org.bds.lang.type.TypeList;

/**
 * Define a map of type list
 * @author pcingola
 */
@SuppressWarnings("rawtypes")
public class ValueList extends Value {

	List value;

	public ValueList(Type type) {
		super(type);
	}

	@Override
	public List get() {
		return value;
	}

	/**
	 * Get element number 'idx' from the list wrapped into a 'Value'
	 */
	public Value getValue(int idx) {
		Object elem = get().get(idx);
		return ((TypeList) type).getElementType().newValue(elem);
	}

	@Override
	public void set(Object v) {
		// TODO: Check list elements class
		value = (List) v;
	}

	public int size() {
		return get().size();
	}

}