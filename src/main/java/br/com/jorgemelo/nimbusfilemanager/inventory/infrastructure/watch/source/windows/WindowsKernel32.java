package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

/**
 * The single Foreign Function &amp; Memory (FFM) binding to the
 * {@code kernel32.dll} functions the Windows change source needs. Every native
 * call in the project goes through here: the {@link Linker},
 * {@link SymbolLookup}, {@code MethodHandle}s and {@code FunctionDescriptor}s
 * live only in this class, so no other class ever touches the FFM calling
 * machinery. Symbols are resolved once into static handles and reused for the
 * process lifetime - a symbol is never looked up nor a descriptor rebuilt per
 * call.
 *
 * <p>
 * Win32 {@code BOOL} is a 32-bit int (nonzero = success) and {@code HANDLE} is
 * a pointer, so handles are modelled as {@link MemorySegment} addresses. The
 * last OS error is captured per call via {@link Linker.Option#captureCallState}
 * and read with {@link #lastError(MemorySegment)} - the FFM equivalent of JNA's
 * old {@code Native.getLastError()}. The class initialises (and thus loads
 * kernel32) lazily on first use, so it stays inert off Windows.
 */
public final class WindowsKernel32 {

	/** {@code INVALID_HANDLE_VALUE} is {@code (HANDLE) -1} on 64-bit Windows. */
	static final long INVALID_HANDLE_VALUE = -1L;

	private static final Linker LINKER = Linker.nativeLinker();
	private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
	private static final StructLayout CAPTURE_LAYOUT = Linker.Option.captureStateLayout();
	private static final Linker.Option CAPTURE_LAST_ERROR = Linker.Option.captureCallState("GetLastError");
	private static final VarHandle LAST_ERROR = CAPTURE_LAYOUT
			.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));

	// HANDLE CreateFileW(name, access, share, securityAttributes, disposition,
	// flags, template).
	private static final MethodHandle CREATE_FILE_W = capturing("CreateFileW",
			FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
	// BOOL ReadDirectoryChangesExW(dir, buffer, len, watchSubtree, filter,
	// bytesReturned, overlapped, completion, informationClass).
	private static final MethodHandle READ_DIRECTORY_CHANGES_EX_W = capturing("ReadDirectoryChangesExW",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS,
					JAVA_INT));
	// BOOL GetVolumePathNameW(fileName, volumePathName, cch) - which mount point
	// a path lives under, which is not always its drive root.
	private static final MethodHandle GET_VOLUME_PATH_NAME_W = capturing("GetVolumePathNameW",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
	// BOOL GetVolumeNameForVolumeMountPointW(mountPoint, volumeName, cch).
	private static final MethodHandle GET_VOLUME_NAME_FOR_MOUNT_POINT_W = capturing(
			"GetVolumeNameForVolumeMountPointW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
	// BOOL GetOverlappedResult(file, overlapped, bytesTransferred, wait).
	private static final MethodHandle GET_OVERLAPPED_RESULT = capturing("GetOverlappedResult",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
	// BOOL DeviceIoControl(device, code, in, inSize, out, outSize, bytesReturned,
	// overlapped).
	private static final MethodHandle DEVICE_IO_CONTROL = capturing("DeviceIoControl",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
	// BOOL CloseHandle(object).
	private static final MethodHandle CLOSE_HANDLE = capturing("CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
	// BOOL CancelIo(file) - the failure path never inspects the error, so it is not
	// captured.
	private static final MethodHandle CANCEL_IO = plain("CancelIo", FunctionDescriptor.of(JAVA_INT, ADDRESS));
	// HANDLE OpenFileById(volumeHint, fileId, access, share, securityAttributes,
	// flags).
	private static final MethodHandle OPEN_FILE_BY_ID = plain("OpenFileById",
			FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
	// DWORD GetFinalPathNameByHandleW(file, buffer, cch, flags).
	private static final MethodHandle GET_FINAL_PATH_NAME_BY_HANDLE_W = plain("GetFinalPathNameByHandleW",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));

	private WindowsKernel32() {
	}

	private static MethodHandle plain(String symbol, FunctionDescriptor descriptor) {
		return LINKER.downcallHandle(find(symbol), descriptor);
	}

	private static MethodHandle capturing(String symbol, FunctionDescriptor descriptor) {
		return LINKER.downcallHandle(find(symbol), descriptor, CAPTURE_LAST_ERROR);
	}

	private static MemorySegment find(String symbol) {
		return KERNEL32.find(symbol).orElseThrow(() -> new UnsatisfiedLinkError("kernel32!" + symbol + " not found"));
	}

	/** Allocates a capture-state segment to pass to the capturing calls below. */
	public static MemorySegment captureState(SegmentAllocator allocator) {
		return allocator.allocate(CAPTURE_LAYOUT);
	}

	/** The OS error recorded by the most recent capturing call on this segment. */
	public static int lastError(MemorySegment captureState) {
		return (int) LAST_ERROR.get(captureState, 0L);
	}

	/** Encodes a Java string as a null-terminated UTF-16LE (wide) C string. */
	public static MemorySegment wideString(SegmentAllocator allocator, String text) {
		return allocator.allocateFrom(text, StandardCharsets.UTF_16LE);
	}

	/**
	 * Whether a {@code CreateFileW}/{@code OpenFileById} result is null or
	 * {@code INVALID_HANDLE_VALUE}.
	 */
	public static boolean isInvalidHandle(MemorySegment handle) {
		return handle == null || handle.address() == 0L || handle.address() == INVALID_HANDLE_VALUE;
	}

	public static MemorySegment createFile(MemorySegment fileName, int desiredAccess, int shareMode,
			int creationDisposition, int flagsAndAttributes, MemorySegment captureState) {
		try {
			return (MemorySegment) CREATE_FILE_W.invoke(captureState, fileName, desiredAccess, shareMode,
					MemorySegment.NULL, creationDisposition, flagsAndAttributes, MemorySegment.NULL);
		} catch (Throwable failure) {
			throw invocationFailed("CreateFileW", failure);
		}
	}

	/**
	 * @param informationClass which shape the OS writes into the buffer. The
	 * extended one carries the file id, and is why this is the {@code Ex} call: a
	 * rename reported without it is two names nothing joins.
	 */
	public static boolean readDirectoryChangesEx(MemorySegment directory, MemorySegment buffer, int bufferLength,
			boolean watchSubtree, int notifyFilter, MemorySegment overlapped, int informationClass,
			MemorySegment captureState) {
		try {
			int result = (int) READ_DIRECTORY_CHANGES_EX_W.invoke(captureState, directory, buffer, bufferLength,
					watchSubtree ? 1 : 0, notifyFilter, MemorySegment.NULL, overlapped, MemorySegment.NULL,
					informationClass);

			return result != 0;
		} catch (Throwable failure) {
			throw invocationFailed("ReadDirectoryChangesExW", failure);
		}
	}

	public static boolean getVolumePathName(MemorySegment fileName, MemorySegment volumePathName, int bufferChars,
			MemorySegment captureState) {
		try {
			return (int) GET_VOLUME_PATH_NAME_W.invoke(captureState, fileName, volumePathName, bufferChars) != 0;
		} catch (Throwable failure) {
			throw invocationFailed("GetVolumePathNameW", failure);
		}
	}

	public static boolean getVolumeNameForVolumeMountPoint(MemorySegment mountPoint, MemorySegment volumeName,
			int bufferChars, MemorySegment captureState) {
		try {
			return (int) GET_VOLUME_NAME_FOR_MOUNT_POINT_W.invoke(captureState, mountPoint, volumeName,
					bufferChars) != 0;
		} catch (Throwable failure) {
			throw invocationFailed("GetVolumeNameForVolumeMountPointW", failure);
		}
	}

	public static boolean getOverlappedResult(MemorySegment file, MemorySegment overlapped,
			MemorySegment bytesTransferred, boolean wait, MemorySegment captureState) {
		try {
			int result = (int) GET_OVERLAPPED_RESULT.invoke(captureState, file, overlapped, bytesTransferred,
					wait ? 1 : 0);

			return result != 0;
		} catch (Throwable failure) {
			throw invocationFailed("GetOverlappedResult", failure);
		}
	}

	public static boolean deviceIoControl(MemorySegment device, int controlCode, MemorySegment inBuffer, int inSize,
			MemorySegment outBuffer, int outSize, MemorySegment bytesReturned, MemorySegment captureState) {
		try {
			int result = (int) DEVICE_IO_CONTROL.invoke(captureState, device, controlCode, inBuffer, inSize, outBuffer,
					outSize, bytesReturned, MemorySegment.NULL);

			return result != 0;
		} catch (Throwable failure) {
			throw invocationFailed("DeviceIoControl", failure);
		}
	}

	public static boolean cancelIo(MemorySegment file) {
		try {
			return (int) CANCEL_IO.invoke(file) != 0;
		} catch (Throwable failure) {
			throw invocationFailed("CancelIo", failure);
		}
	}

	public static boolean closeHandle(MemorySegment object, MemorySegment captureState) {
		try {
			return (int) CLOSE_HANDLE.invoke(captureState, object) != 0;
		} catch (Throwable failure) {
			throw invocationFailed("CloseHandle", failure);
		}
	}

	public static MemorySegment openFileById(MemorySegment volumeHint, MemorySegment fileId, int desiredAccess,
			int shareMode, int flagsAndAttributes) {
		try {
			return (MemorySegment) OPEN_FILE_BY_ID.invoke(volumeHint, fileId, desiredAccess, shareMode,
					MemorySegment.NULL, flagsAndAttributes);
		} catch (Throwable failure) {
			throw invocationFailed("OpenFileById", failure);
		}
	}

	public static int getFinalPathNameByHandle(MemorySegment file, MemorySegment buffer, int bufferChars, int flags) {
		try {
			return (int) GET_FINAL_PATH_NAME_BY_HANDLE_W.invoke(file, buffer, bufferChars, flags);
		} catch (Throwable failure) {
			throw invocationFailed("GetFinalPathNameByHandleW", failure);
		}
	}

	// A downcall only throws for a JVM-level fault (a closed arena, a wrong-thread
	// access): never for a Win32 failure, which surfaces as a
	// false/INVALID_HANDLE_VALUE return the caller already handles.
	private static RuntimeException invocationFailed(String symbol, Throwable failure) {
		if (failure instanceof Error error) {
			throw error;
		}

		return new IllegalStateException("kernel32!" + symbol + " invocation failed", failure);
	}
}