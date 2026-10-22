package com.drzewiecki.shitcloud.data;

import com.drzewiecki.shitcloud.controller.AppController;
import com.drzewiecki.shitcloud.controller.DataController;
import com.drzewiecki.shitcloud.controller.DepsController;
import com.drzewiecki.shitcloud.controller.HistoryController;
import com.drzewiecki.shitcloud.controller.MigrationController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Gives Data API errors a body apps can use. Scoped to the controllers that raise them (the Data API, and the app
 * registry's data download and data changes, the dependency log, migrations, the Data & history page): Spring's default error body hides reasons on purpose, and the rest of the API keeps it.
 */
@RestControllerAdvice(assignableTypes = {DataController.class, AppController.class, DepsController.class,
        MigrationController.class, HistoryController.class})
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
