package deadhead.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions into RFC-7807 problem responses the page can display.
 *
 * The two typed cases carry messages written for a pilot to read, so they are
 * passed through. Everything else is a bug: it goes to the log in full and
 * comes back as one flat sentence, because an exception message can name a
 * table, a column, or a URL with credentials in it.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ProblemDetail tooBig(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
            "That calendar is too large (10 MB max).");
    }

    /**
     * Spring's own web exceptions already carry the right status — a wrong URL
     * is a 404, a wrong verb a 405. Letting them fall into the catch-all below
     * would relabel every one of them as "we broke", which is both wrong and
     * genuinely confusing to debug.
     */
    @ExceptionHandler(org.springframework.web.ErrorResponseException.class)
    ProblemDetail springError(org.springframework.web.ErrorResponseException e) {
        return e.getBody();
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ProblemDetail notFound(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No such endpoint.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail serverError(Exception e) {
        log.error("unhandled request failure", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
            "Something went wrong on our end.");
    }
}
