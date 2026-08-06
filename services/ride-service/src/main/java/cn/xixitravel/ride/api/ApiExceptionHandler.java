package cn.xixitravel.ride.api;

import cn.xixitravel.ride.knowledge.KnowledgeSearchUnavailableException;
import cn.xixitravel.ride.confirmation.RideConfirmationException;
import cn.xixitravel.ride.service.IdempotencyConflictException;
import cn.xixitravel.ride.service.RideNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RideNotFoundException.class)
    ProblemDetail handleNotFound(RideNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(KnowledgeSearchUnavailableException.class)
    ProblemDetail handleKnowledgeUnavailable(KnowledgeSearchUnavailableException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                exception.getMessage()
        );
    }

    @ExceptionHandler({
            RideConfirmationException.class,
            IdempotencyConflictException.class
    })
    ProblemDetail handleConflict(RuntimeException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            IllegalStateException.class,
            MethodArgumentNotValidException.class
    })
    ProblemDetail handleBadRequest(Exception exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
}
