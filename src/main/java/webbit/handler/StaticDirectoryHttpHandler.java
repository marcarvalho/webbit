package webbit.handler;

import webbit.HttpHandler;
import webbit.HttpRequest;
import webbit.HttpResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import static java.util.concurrent.Executors.newFixedThreadPool;

public class StaticDirectoryHttpHandler implements HttpHandler {

    public static final Map<String, String> DEFAULT_MIME_TYPES;

    static {
        // This is not an exhaustive list, just the most common types. Call registerMimeType() to add more.
        Map<String, String> mimeTypes = new HashMap<String, String>();
        mimeTypes.put("txt", "text/plain");
        mimeTypes.put("css", "text/css");
        mimeTypes.put("csv", "text/csv");
        mimeTypes.put("htm", "text/html");
        mimeTypes.put("html", "text/html");
        mimeTypes.put("xml", "text/xml");
        mimeTypes.put("js", "text/javascript"); // Technically it should be application/javascript (RFC 4329), but IE8 struggles with that
        mimeTypes.put("xhtml", "application/xhtml+xml");
        mimeTypes.put("json", "application/json");
        mimeTypes.put("pdf", "application/pdf");
        mimeTypes.put("zip", "application/zip");
        mimeTypes.put("tar", "application/x-tar");
        mimeTypes.put("gif", "image/gif");
        mimeTypes.put("jpeg", "image/jpeg");
        mimeTypes.put("jpg", "image/jpeg");
        mimeTypes.put("tiff", "image/tiff");
        mimeTypes.put("tif", "image/tiff");
        mimeTypes.put("png", "image/png");
        mimeTypes.put("svg", "image/svg+xml");
        mimeTypes.put("ico", "image/vnd.microsoft.icon");
        DEFAULT_MIME_TYPES = Collections.unmodifiableMap(mimeTypes);
    }

    private static final String DEFAULT_WELCOME_FILE = "index.html";

    private final File dir;
    private final Executor webThread;
    private final Executor ioThread;
    private final Map<String, String> mimeTypes;
    private String welcomeFile;

    public StaticDirectoryHttpHandler(File dir, Executor webThread, Executor ioThread) {
        this.dir = dir;
        this.webThread = webThread;
        this.ioThread = ioThread;
        this.mimeTypes = new HashMap<String, String>(DEFAULT_MIME_TYPES);
        this.welcomeFile = DEFAULT_WELCOME_FILE;
    }

    public StaticDirectoryHttpHandler(File dir, Executor webThread) {
        this(dir, webThread, newFixedThreadPool(4));
    }

    public StaticDirectoryHttpHandler addMimeType(String extension, String mimeType) {
        mimeTypes.put(extension, mimeType);
        return this;
    }

    public StaticDirectoryHttpHandler welcomeFile(String welcomeFile) {
        this.welcomeFile = welcomeFile;
        return this;
    }

    @Override
    public void handleHttpRequest(HttpRequest request, final HttpResponse response) throws Exception {
        // Switch from web thead to IO thread, so we don't block web server when we access the filesystem.
        ioThread.execute(new IOWorker(dir, request.uri(), welcomeFile) {
            @Override
            protected void notFound() {
                // Switch back from IO thread to web thread.
                webThread.execute(new Runnable() {
                    @Override
                    public void run() {
                        response.status(404).end();
                    }
                });
            }

            @Override
            protected void serve(final String filename, final byte[] contents) {
                // Switch back from IO thread to web thread.
                webThread.execute(new Runnable() {
                    @Override
                    public void run() {
                        // TODO: Don't read all into memory, instead use zero-copy.
                        // TODO: Check bytes read match expected encoding of mime-type
                        response.header("Content-Type", guessMimeType(filename, response))
                                .header("Content-Length", contents.length)
                                .content(contents)
                                .end();
                    }
                });
            }

            @Override
            protected void error(final IOException exception) {
                // Switch back from IO thread to web thread.
                webThread.execute(new Runnable() {
                    @Override
                    public void run() {
                        response.error(exception);
                    }
                });
            }
        });
    }

    private String guessMimeType(String path, HttpResponse response) {
        int lastDot = path.lastIndexOf('.');
        if (lastDot == -1) {
            return null;
        }
        String extension = path.substring(lastDot + 1).toLowerCase();
        String mimeType = mimeTypes.get(extension);
        if (mimeType == null) {
            return null;
        }
        if (mimeType.startsWith("text/") && response.charset() != null) {
            mimeType += "; charset=" + response.charset().name();
        }
        return mimeType;
    }

    /**
     * All IO is performed by this worker on a separate thread, so we never block the HttpHandler.
     */
    private abstract static class IOWorker implements Runnable {

        private final File root;
        private final String path;
        private final String welcomeFile;

        private IOWorker(File root, String path, String welcomeFile) {
            this.root = root;
            this.path = path;
            this.welcomeFile = welcomeFile;
        }

        @Override
        public void run() {
            // TODO: Cache
            try {
                File file = resolveFile(path);
                if (!file.exists()) {
                    notFound();
                } else if (!file.isDirectory()) {
                    serve(file.getName(), read(file));
                } else {
                    file = new File(file, welcomeFile);
                    if (file.exists()) {
                        serve(file.getName(), read(file));
                    } else {
                        notFound();
                    }
                }
            } catch (IOException e) {
                error(e);
            }
        }

        private byte[] read(File file) throws IOException {
            byte[] data = new byte[(int) file.length()];
            FileInputStream in = new FileInputStream(file);
            try {
                in.read(data);
            } finally {
                in.close();
            }
            return data;
        }

        private File resolveFile(String path) throws IOException {
            // MEGA TODO: Validate that file resides in dir (i.e. client cannot access ../../../etc/passwd)
            // TODO: Ignore query params
            return new File(root, path).getCanonicalFile();
        }

        protected abstract void notFound();

        protected abstract void serve(String filename, byte[] contents);

        protected abstract void error(IOException exception);
    }
}
