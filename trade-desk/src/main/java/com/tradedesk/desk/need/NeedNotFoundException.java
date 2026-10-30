package com.tradedesk.desk.need;

/** No need with this id on this desk (404). */
public class NeedNotFoundException extends RuntimeException {

	public NeedNotFoundException() {
		super("There is no need with this id.");
	}

}
