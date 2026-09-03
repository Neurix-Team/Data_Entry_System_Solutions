package com.dataentry.model;

/** What a chunked upload session turns into once every chunk has landed. */
public enum UploadTarget {
    /** Folder quick-upload: the file becomes its own REVIEW-status ticket in a project. */
    QUICK_UPLOAD,
    /** Attach the file to an existing ticket the caller owns (or any ticket, for admins). */
    TICKET_DOCUMENT
}
