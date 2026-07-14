package deadhead.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns exceptions into RFC-7807 problem responses the page can display. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
            e.getMessage() == null ? "Bad request" : e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail unplannable(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
            e.getMessage() == null ? "Couldn't plan that week" : e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail serverError(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
            e.getClass().getSimpleName() + ": " + e.getMessage());
    }
}
