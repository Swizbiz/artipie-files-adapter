/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 artipie.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.artipie.files;

import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.blocking.BlockingStorage;
import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.vertx.VertxSliceServer;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.ext.web.client.HttpResponse;
import io.vertx.reactivex.ext.web.client.WebClient;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for files adapter.
 * @since 0.5
 */
final class FileSliceITCase {

    /**
     * The host to send requests to.
     */
    private static final String HOST = "localhost";

    /**
     * The port of slice server.
     */
    private int port;

    /**
     * Vertx instance.
     */
    private Vertx vertx;

    /**
     * Storage for server.
     */
    private Storage storage;

    /**
     * Slice server.
     */
    private VertxSliceServer server;

    @BeforeEach
    void setUp() {
        this.vertx = Vertx.vertx();
        this.storage = new InMemoryStorage();
        this.server = new VertxSliceServer(this.vertx, new FilesSlice(this.storage));
        this.port = this.server.start();
    }

    @AfterEach
    void tearDown() {
        this.server.stop();
        this.vertx.close();
    }

    @Test
    void testUploadFile() throws Exception {
        final String hello = "Hello world!!!";
        final WebClient web = WebClient.create(this.vertx);
        web.put(this.port, FileSliceITCase.HOST, "/hello.txt")
            .rxSendBuffer(Buffer.buffer(hello.getBytes()))
            .blockingGet();
        MatcherAssert.assertThat(
            new String(
                new BlockingStorage(this.storage).value(new Key.From("hello.txt")),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>(hello)
        );
    }

    @Test
    void testUploadFileWithComplexName() throws Exception {
        final String hello = "Hello world!!!!";
        final WebClient web = WebClient.create(this.vertx);
        web.put(this.port, FileSliceITCase.HOST, "/hello/world.txt")
            .rxSendBuffer(Buffer.buffer(hello.getBytes()))
            .blockingGet();
        MatcherAssert.assertThat(
            new String(
                new BlockingStorage(this.storage).value(new Key.From("hello/world.txt")),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>(hello)
        );
    }

    @Test
    void testDownloadsFilesWithComplexName() throws Exception {
        final String hello = "Hellooo world!!";
        final WebClient web = WebClient.create(this.vertx);
        final String hellot = "hello/world1.txt";
        new BlockingStorage(this.storage).save(new Key.From(hellot), hello.getBytes());
        MatcherAssert.assertThat(
            new String(
                web.get(this.port, FileSliceITCase.HOST, String.format("/%s", hellot))
                    .rxSend()
                    .blockingGet()
                    .bodyAsBuffer()
                    .getBytes(),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>(hello)
        );
    }

    @Test
    void testDownloadsFile() throws Exception {
        final String hello = "Hello world!!";
        final WebClient web = WebClient.create(this.vertx);
        final String hellot = "hello1.txt";
        new BlockingStorage(this.storage).save(new Key.From(hellot), hello.getBytes());
        MatcherAssert.assertThat(
            new String(
                web.get(this.port, FileSliceITCase.HOST, "/hello1.txt")
                    .rxSend()
                    .blockingGet()
                    .bodyAsBuffer()
                    .getBytes(),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>(hello)
        );
    }

    @ParameterizedTest
    @ValueSource (strings = { "/foo/bar", "/foo/bar/" })
    void testBlobListInPlainText(final String uri) {
        final WebClient web = WebClient.create(this.vertx);
        final BlockingStorage sto = new BlockingStorage(this.storage);
        final String fone = "foo/bar/file1.txt";
        sto.save(new Key.From(fone), "file 1 content".getBytes());
        final String ftwo = "foo/bar/file2.txt";
        sto.save(new Key.From(ftwo), "file 2 content".getBytes());
        final String fthree = "foo/bar/file3.txt";
        sto.save(new Key.From(fthree), "file 3 content".getBytes());
        final HttpResponse<Buffer> response = web.get(this.port, FileSliceITCase.HOST, uri)
            .putHeader("Accept", FilesSlice.PLAIN_TEXT)
            .rxSend()
            .blockingGet();
        MatcherAssert.assertThat(
            "Blobs should be listed in plain text.",
            new String(
                response.bodyAsBuffer().getBytes(),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>(
                Arrays.asList(fone, ftwo, fthree)
                    .stream()
                    .collect(Collectors.joining("\n"))
            )
        );
        MatcherAssert.assertThat(
            "Content type should be in plain text.",
            response.headers().get("Content-Type"),
            new IsEqual<>(FilesSlice.PLAIN_TEXT)
        );
    }

    @Test
    void testDeletesFile() throws Exception {
        final String hello = "Hello world!";
        final WebClient web = WebClient.create(this.vertx);
        final String hellot = "hell.txt";
        new BlockingStorage(this.storage).save(new Key.From(hellot), hello.getBytes());
        web.delete(this.port, FileSliceITCase.HOST, "/hell.txt")
            .rxSend()
            .blockingGet();
        MatcherAssert.assertThat(
            new BlockingStorage(this.storage).exists(new Key.From(hellot)),
            new IsEqual<>(false)
        );
    }
}
