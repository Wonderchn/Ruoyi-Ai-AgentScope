package org.ruoyi.ai.api.runtime;


/** Neutral Worker capability contract; implementations belong to the RAG/infra modules. */
public interface MinerUPort {
    public static class MinerUUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public MinerUUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class JobLostException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JobLostException(String jobId) {
            super("mineru job not found after service restart: " + jobId);
        }
    }

    public record ParseResult(String markdown, String markdownSha256, String jobId, String fileId,
                              String tier, String parserVersion, Long durationMs, String pageRange,
                              String structuredContent, String structuredSha256) {
    }

    public record ParseJob(String jobId,String fileId) {}

    public static class ParseCancelledException extends RuntimeException { }
    ParseResult parse(byte[] pdfBytes, String filename, String sha256, int deadlineSeconds);
    ParseJob submit(byte[] pdfBytes, String filename, String sha256);
    ParseResult awaitResult(ParseJob submitted, int deadlineSeconds);
    ParseResult awaitResult(ParseJob submitted, int deadlineSeconds, java.util.function.BooleanSupplier cancelled);
    void cancel(String jobId);
}
