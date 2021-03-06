/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ragnarok.fenrir.media.exo;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayerLibraryInfo;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSourceException;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;
import com.google.common.base.Predicate;

import org.jetbrains.annotations.NotNull;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static com.google.android.exoplayer2.util.Util.castNonNull;
import static java.lang.Math.min;

/**
 * An {@link HttpDataSource} that delegates to Square's {@link Call.Factory}.
 *
 * <p>Note: HTTP request headers will be set using all parameters passed via (in order of decreasing
 * priority) the {@code dataSpec}, {@link #setRequestProperty} and the default parameters used to
 * construct the instance.
 */
public class OkHttpDataSource extends BaseDataSource implements HttpDataSource {

    private static final byte[] SKIP_BUFFER = new byte[4096];

    static {
        ExoPlayerLibraryInfo.registerModule("goog.exo.okhttp");
    }

    private final Call.Factory callFactory;
    private final RequestProperties requestProperties;

    @Nullable
    private final String userAgent;
    @Nullable
    private final CacheControl cacheControl;
    @Nullable
    private final RequestProperties defaultRequestProperties;

    @Nullable
    private Predicate<String> contentTypePredicate;
    @Nullable
    private DataSpec dataSpec;
    @Nullable
    private Response response;
    @Nullable
    private InputStream responseByteStream;
    private boolean opened;

    private long bytesToSkip;
    private long bytesToRead;

    private long bytesSkipped;
    private long bytesRead;

    /**
     * Creates an instance.
     *
     * @param callFactory              A {@link Call.Factory} (typically an {@link okhttp3.OkHttpClient}) for use
     *                                 by the source.
     * @param userAgent                An optional User-Agent string.
     * @param cacheControl             An optional {@link CacheControl} for setting the Cache-Control header.
     * @param defaultRequestProperties Optional default {@link RequestProperties} to be sent to the
     *                                 server as HTTP headers on every request.
     */
    public OkHttpDataSource(
            Call.Factory callFactory,
            @Nullable String userAgent,
            @Nullable CacheControl cacheControl,
            @Nullable RequestProperties defaultRequestProperties) {
        super(/* isNetwork= */ true);
        this.callFactory = Assertions.checkNotNull(callFactory);
        this.userAgent = userAgent;
        this.cacheControl = cacheControl;
        this.defaultRequestProperties = defaultRequestProperties;
        requestProperties = new RequestProperties();
    }

    /**
     * Sets a content type {@link Predicate}. If a content type is rejected by the predicate then a
     * {@link HttpDataSource.InvalidContentTypeException} is thrown from {@link #open(DataSpec)}.
     *
     * @param contentTypePredicate The content type {@link Predicate}, or {@code null} to clear a
     *                             predicate that was previously set.
     */
    public void setContentTypePredicate(@Nullable Predicate<String> contentTypePredicate) {
        this.contentTypePredicate = contentTypePredicate;
    }

    @Override
    @Nullable
    public Uri getUri() {
        return response == null ? null : Uri.parse(response.request().url().toString());
    }

    @Override
    public int getResponseCode() {
        return response == null ? -1 : response.code();
    }

    @NotNull
    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return response == null ? Collections.emptyMap() : response.headers().toMultimap();
    }

    @Override
    public void setRequestProperty(@NotNull String name, @NotNull String value) {
        Assertions.checkNotNull(name);
        Assertions.checkNotNull(value);
        requestProperties.set(name, value);
    }

    @Override
    public void clearRequestProperty(@NotNull String name) {
        Assertions.checkNotNull(name);
        requestProperties.remove(name);
    }

    @Override
    public void clearAllRequestProperties() {
        requestProperties.clear();
    }

    @Override
    public long open(@NotNull DataSpec dataSpec) throws HttpDataSourceException {
        this.dataSpec = dataSpec;
        bytesRead = 0;
        bytesSkipped = 0;
        transferInitializing(dataSpec);

        Request request = makeRequest(dataSpec);
        Response response;
        ResponseBody responseBody;
        try {
            this.response = callFactory.newCall(request).execute();
            response = this.response;
            responseBody = Assertions.checkNotNull(response.body());
            responseByteStream = responseBody.byteStream();
        } catch (IOException e) {
            @Nullable String message = e.getMessage();
            if (message != null
                    && Util.toLowerInvariant(message).matches("cleartext communication.*not permitted.*")) {
                throw new CleartextNotPermittedException(e, dataSpec);
            }
            throw new HttpDataSourceException(
                    "Unable to connect", e, dataSpec, HttpDataSourceException.TYPE_OPEN);
        }

        int responseCode = response.code();

        // Check for a valid response code.
        if (!response.isSuccessful()) {
            byte[] errorResponseBody;
            try {
                errorResponseBody = Util.toByteArray(Assertions.checkNotNull(responseByteStream));
            } catch (IOException e) {
                throw new HttpDataSourceException(
                        "Error reading non-2xx response body", e, dataSpec, HttpDataSourceException.TYPE_OPEN);
            }
            Map<String, List<String>> headers = response.headers().toMultimap();
            closeConnectionQuietly();
            InvalidResponseCodeException exception =
                    new InvalidResponseCodeException(
                            responseCode, response.message(), headers, dataSpec, errorResponseBody);
            if (responseCode == 416) {
                exception.initCause(new DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE));
            }
            throw exception;
        }

        // Check for a valid content type.
        MediaType mediaType = responseBody.contentType();
        String contentType = mediaType != null ? mediaType.toString() : "";
        if (contentTypePredicate != null && !contentTypePredicate.apply(contentType)) {
            closeConnectionQuietly();
            throw new InvalidContentTypeException(contentType, dataSpec);
        }

        // If we requested a range starting from a non-zero position and received a 200 rather than a
        // 206, then the server does not support partial requests. We'll need to manually skip to the
        // requested position.
        bytesToSkip = responseCode == 200 && dataSpec.position != 0 ? dataSpec.position : 0;

        // Determine the length of the data to be read, after skipping.
        if (dataSpec.length != C.LENGTH_UNSET) {
            bytesToRead = dataSpec.length;
        } else {
            long contentLength = responseBody.contentLength();
            bytesToRead = contentLength != -1 ? (contentLength - bytesToSkip) : C.LENGTH_UNSET;
        }

        opened = true;
        transferStarted(dataSpec);

        return bytesToRead;
    }

    @Override
    public int read(@NotNull byte[] buffer, int offset, int readLength) throws HttpDataSourceException {
        try {
            skipInternal();
            return readInternal(buffer, offset, readLength);
        } catch (IOException e) {
            throw new HttpDataSourceException(
                    e, Assertions.checkNotNull(dataSpec), HttpDataSourceException.TYPE_READ);
        }
    }

    @Override
    public void close() {
        if (opened) {
            opened = false;
            transferEnded();
            closeConnectionQuietly();
        }
    }

    /**
     * Returns the number of bytes that have been skipped since the most recent call to
     * {@link #open(DataSpec)}.
     *
     * @return The number of bytes skipped.
     */
    protected final long bytesSkipped() {
        return bytesSkipped;
    }

    /**
     * Returns the number of bytes that have been read since the most recent call to
     * {@link #open(DataSpec)}.
     *
     * @return The number of bytes read.
     */
    protected final long bytesRead() {
        return bytesRead;
    }

    /**
     * Establishes a connection.
     */
    private Request makeRequest(DataSpec dataSpec) throws HttpDataSourceException {
        long position = dataSpec.position;
        long length = dataSpec.length;

        HttpUrl url = HttpUrl.parse(dataSpec.uri.toString());
        if (url == null) {
            throw new HttpDataSourceException(
                    "Malformed URL", dataSpec, HttpDataSourceException.TYPE_OPEN);
        }

        Request.Builder builder = new Request.Builder().url(url);
        if (cacheControl != null) {
            builder.cacheControl(cacheControl);
        }

        Map<String, String> headers = new HashMap<>();
        if (defaultRequestProperties != null) {
            headers.putAll(defaultRequestProperties.getSnapshot());
        }

        headers.putAll(requestProperties.getSnapshot());
        headers.putAll(dataSpec.httpRequestHeaders);

        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        if (!(position == 0 && length == C.LENGTH_UNSET)) {
            String rangeRequest = "bytes=" + position + "-";
            if (length != C.LENGTH_UNSET) {
                rangeRequest += (position + length - 1);
            }
            builder.addHeader("Range", rangeRequest);
        }
        if (userAgent != null) {
            builder.addHeader("User-Agent", userAgent);
        }
        if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
            builder.addHeader("Accept-Encoding", "identity");
        }

        @Nullable RequestBody requestBody = null;
        if (dataSpec.httpBody != null) {
            requestBody = RequestBody.create(dataSpec.httpBody, null);
        } else if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST) {
            // OkHttp requires a non-null body for POST requests.
            requestBody = RequestBody.create(Util.EMPTY_BYTE_ARRAY, null);
        }
        builder.method(dataSpec.getHttpMethodString(), requestBody);
        return builder.build();
    }

    /**
     * Skips any bytes that need skipping. Else does nothing.
     * <p>
     * This implementation is based roughly on {@code libcore.io.Streams.skipByReading()}.
     *
     * @throws InterruptedIOException If the thread is interrupted during the operation.
     * @throws EOFException           If the end of the input stream is reached before the bytes are skipped.
     */
    private void skipInternal() throws IOException {
        if (bytesSkipped == bytesToSkip) {
            return;
        }

        while (bytesSkipped != bytesToSkip) {
            int readLength = (int) min(bytesToSkip - bytesSkipped, SKIP_BUFFER.length);
            int read = castNonNull(responseByteStream).read(SKIP_BUFFER, 0, readLength);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException();
            }
            if (read == -1) {
                throw new EOFException();
            }
            bytesSkipped += read;
            bytesTransferred(read);
        }
    }

    /**
     * Reads up to {@code length} bytes of data and stores them into {@code buffer}, starting at
     * index {@code offset}.
     * <p>
     * This method blocks until at least one byte of data can be read, the end of the opened range is
     * detected, or an exception is thrown.
     *
     * @param buffer     The buffer into which the read data should be stored.
     * @param offset     The start offset into {@code buffer} at which data should be written.
     * @param readLength The maximum number of bytes to read.
     * @return The number of bytes read, or {@link C#RESULT_END_OF_INPUT} if the end of the opened
     * range is reached.
     * @throws IOException If an error occurs reading from the source.
     */
    private int readInternal(byte[] buffer, int offset, int readLength) throws IOException {
        if (readLength == 0) {
            return 0;
        }
        if (bytesToRead != C.LENGTH_UNSET) {
            long bytesRemaining = bytesToRead - bytesRead;
            if (bytesRemaining == 0) {
                return C.RESULT_END_OF_INPUT;
            }
            readLength = (int) min(readLength, bytesRemaining);
        }

        int read = castNonNull(responseByteStream).read(buffer, offset, readLength);
        if (read == -1) {
            if (bytesToRead != C.LENGTH_UNSET) {
                // End of stream reached having not read sufficient data.
                throw new EOFException();
            }
            return C.RESULT_END_OF_INPUT;
        }

        bytesRead += read;
        bytesTransferred(read);
        return read;
    }

    /**
     * Closes the current connection quietly, if there is one.
     */
    private void closeConnectionQuietly() {
        if (response != null) {
            Assertions.checkNotNull(response.body()).close();
            response = null;
        }
        responseByteStream = null;
    }

}
