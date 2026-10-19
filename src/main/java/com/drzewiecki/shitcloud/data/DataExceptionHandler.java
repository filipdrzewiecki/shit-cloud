package com.drzewiecki.shitcloud.data;

import com.drzewiecki.shitcloud.controller.DataController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Gives Data API errors a body apps can use. Scoped to the data controller: Spring's default error body
 * hides reasons on purpose, and the rest of the API keeps it that way.
 */
@RestControllerAdvice(assignableTypes = DataController.class)
public class DataExceptionHandler {

    @ExceptionHandler(DataException.class)
    public ResponseEntity<DataError> dataException(DataException e) {
        return ResponseEntity.status(e.status()).body(new DataError(e.code(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<DataError> unreadableBody() {
        return dataException(DataException.badRequest("The request body is not valid JSON of the expected shape"));
    }
}
