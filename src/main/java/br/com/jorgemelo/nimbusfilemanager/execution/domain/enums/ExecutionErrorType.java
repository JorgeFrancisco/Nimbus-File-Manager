package br.com.jorgemelo.nimbusfilemanager.execution.domain.enums;

/**
 * Why a single file failed inside an execution. Recorded per file so the
 * execution screen can name what went wrong instead of only counting it - the
 * conversion used to report "1 error" over an empty list.
 */
public enum ExecutionErrorType {

	CRC_ERROR, ACCESS_DENIED, FILE_NOT_FOUND, HASH_ERROR, METADATA_ERROR, CONVERSION_ERROR, MOVE_ERROR, UNKNOWN
}