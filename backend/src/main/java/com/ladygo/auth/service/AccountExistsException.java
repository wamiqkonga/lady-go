package com.ladygo.auth.service;

import com.ladygo.common.exception.ApiException;
import com.ladygo.common.exception.ErrorCode;

/** Registration was refused because the phone or email is already on an account; the message does not say which. */
public class AccountExistsException extends ApiException {

    public AccountExistsException() {
        super(ErrorCode.ACCOUNT_EXISTS);
    }
}
