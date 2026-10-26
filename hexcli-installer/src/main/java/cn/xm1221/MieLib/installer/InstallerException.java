package cn.xm1221.MieLib.installer;

/** A fatal, user-facing installer error. The message is printed as-is. */
final class InstallerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InstallerException(String message) {
        super(message);
    }

    InstallerException(String message, Throwable cause) {
        super(message, cause);
    }
}
