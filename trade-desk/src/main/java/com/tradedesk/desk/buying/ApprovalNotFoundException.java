package com.tradedesk.desk.buying;

/** No approval with this id on this desk (404). */
public class ApprovalNotFoundException extends RuntimeException {

	public ApprovalNotFoundException() {
		super("There is no approval with this id.");
	}

}
