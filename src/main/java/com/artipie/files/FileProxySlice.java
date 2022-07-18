/*
 * The MIT License (MIT) Copyright (c) 2020-2022 artipie.com
 * https://github.com/artipie/files-adapter/blob/master/LICENSE.txt
 */
package com.artipie.files;

import com.artipie.asto.Content;
import com.artipie.asto.Storage;
import com.artipie.asto.cache.Cache;
import com.artipie.asto.cache.CacheControl;
import com.artipie.asto.cache.FromRemoteCache;
import com.artipie.asto.cache.Remote;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.async.AsyncResponse;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.client.UriClientSlice;
import com.artipie.http.client.auth.AuthClientSlice;
import com.artipie.http.client.auth.Authenticator;
import com.artipie.http.rq.RequestLineFrom;
import com.artipie.http.rs.RsFull;
import com.artipie.http.rs.RsStatus;
import com.artipie.http.rs.RsWithStatus;
import com.artipie.http.slice.KeyFromPath;
import io.reactivex.Flowable;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.reactivestreams.Publisher;

/**
 * Binary files proxy {@link Slice} implementation.
 * @since 0.4
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 */
public final class FileProxySlice implements Slice {

    /**
     * Remote slice.
     */
    private final Slice remote;

    /**
     * Cache.
     */
    private final Cache cache;

    /**
     * New files proxy slice.
     * @param clients HTTP clients
     * @param remote Remote URI
     */
    public FileProxySlice(final ClientSlices clients, final URI remote) {
        this(new UriClientSlice(clients, remote), Cache.NOP);
    }

    /**
     * New files proxy slice.
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param auth Authenticator
     * @param asto Cache storage
     * @checkstyle ParameterNumberCheck (500 lines)
     */
    public FileProxySlice(final ClientSlices clients, final URI remote,
        final Authenticator auth, final Storage asto) {
        this(
            new AuthClientSlice(new UriClientSlice(clients, remote), auth),
            new FromRemoteCache(asto)
        );
    }

    /**
     * New files proxy slice.
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param auth Authenticator
     * @param cache Cache instance
     * @checkstyle ParameterNumberCheck (500 lines)
     */
    public FileProxySlice(final ClientSlices clients, final URI remote,
        final Authenticator auth, final Cache cache) {
        this(new AuthClientSlice(new UriClientSlice(clients, remote), auth), cache);
    }

    /**
     * Ctor.
     *
     * @param remote Remote slice
     * @param cache Cache
     */
    FileProxySlice(final Slice remote, final Cache cache) {
        this.remote = remote;
        this.cache = cache;
    }

    @Override
    public Response response(
        final String line, final Iterable<Map.Entry<String, String>> ignored,
        final Publisher<ByteBuffer> pub
    ) {
        final AtomicReference<Headers> headers = new AtomicReference<>();
        return new AsyncResponse(
            this.cache.load(
                new KeyFromPath(new RequestLineFrom(line).uri().getPath()),
                new Remote.WithErrorHandling(
                    () -> {
                        final CompletableFuture<Optional<? extends Content>> promise =
                            new CompletableFuture<>();
                        this.remote.response(line, Headers.EMPTY, Content.EMPTY).send(
                            (rsstatus, rsheaders, rsbody) -> {
                                final CompletableFuture<Void> term = new CompletableFuture<>();
                                headers.set(rsheaders);
                                if (rsstatus.success()) {
                                    final Flowable<ByteBuffer> body = Flowable.fromPublisher(rsbody)
                                        .doOnError(term::completeExceptionally)
                                        .doOnTerminate(() -> term.complete(null));
                                    promise.complete(Optional.of(new Content.From(body)));
                                } else {
                                    promise.complete(Optional.empty());
                                }
                                return term;
                            }
                        );
                        return promise;
                    }
                ),
                CacheControl.Standard.ALWAYS
            ).handle(
                (content, throwable) -> {
                    final CompletableFuture<Response> result = new CompletableFuture<>();
                    if (throwable == null && content.isPresent()) {
                        result.complete(
                            new RsFull(RsStatus.OK, new Headers.From(headers.get()), content.get())
                        );
                    } else {
                        result.complete(new RsWithStatus(RsStatus.NOT_FOUND));
                    }
                    return result;
                }
            ).thenCompose(Function.identity())
        );
    }
}
