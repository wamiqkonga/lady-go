package com.ladygo.storage;

import com.ladygo.common.exception.ApiException;
import com.ladygo.common.exception.ErrorCode;

/** A storage-provider failure (disk I/O, S3 outage) - not the caller's fault. */
public class FileStorageException extends ApiException {

    public FileStorageException(Throwable cause) {
        super(ErrorCode.INTERNAL_ERROR);
        if (cause != null) {
            initCause(cause);
        }
    }
}
