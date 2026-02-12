package com.bugzero.rarego.global.exception;

import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.global.response.ExceptionResponseDto;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
	@ExceptionHandler(CustomException.class)
	public ExceptionResponseDto handleCustomException(CustomException e) {
		log.error("CustomException 발생: {}", e.getMessage());
		return ExceptionResponseDto.from(e.getErrorType(), e.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ExceptionResponseDto handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
		log.error("MethodArgumentNotValidException 발생: {}", e.getMessage());

		// 에러가 발생한 필드 중 첫 번째 필드의 에러 메시지만 가져온다.
		String errorMessage = e.getBindingResult()
			.getAllErrors()
			.getFirst()
			.getDefaultMessage();

		return ExceptionResponseDto.from(ErrorType.INVALID_INPUT, errorMessage);
	}

	@ExceptionHandler(InvalidDataAccessApiUsageException.class)
	public ExceptionResponseDto handleInvalidDataAccessApiUsageException(
		InvalidDataAccessApiUsageException e) {
		log.error("InvalidDataAccessApiUsageException 발생 (정렬 파라미터 오류 등): {}", e.getMessage());

		return ExceptionResponseDto.from(ErrorType.INVALID_INPUT, "정렬 파라미터가 잘못되었습니다.");
	}

	@ExceptionHandler(Exception.class)
	public ExceptionResponseDto handleException(Exception e, HttpServletRequest request) {
		log.error("[Unhandled Exception] - URL: {} {}, Error: {}",
			request.getMethod(),
			request.getRequestURI(),
			e.getMessage(),
			e);
		return ExceptionResponseDto.from(ErrorType.INTERNAL_SERVER_ERROR, e.getMessage());
	}
}