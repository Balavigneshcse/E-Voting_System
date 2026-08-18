package Backend.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Buffers the request body so it can be read twice.
 *
 * <p>Signature verification has to hash the raw body, but a servlet input stream is
 * single-pass — reading it in a filter would leave nothing for the controller. This
 * wrapper reads once, keeps the bytes, and replays them downstream.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    public byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream replay = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public boolean isFinished()  { return replay.available() == 0; }
            @Override public boolean isReady()     { return true; }
            @Override public void setReadListener(ReadListener listener) { /* not used */ }
            @Override public int read()            { return replay.read(); }
            @Override public int available()       { return replay.available(); }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
