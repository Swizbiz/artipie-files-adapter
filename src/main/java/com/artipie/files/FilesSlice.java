/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/files-adapter/blob/master/LICENSE.txt
 */
package com.artipie.files;

import com.artipie.asto.Storage;
import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.http.Headers;
import com.artipie.http.Slice;
import com.artipie.http.auth.Action;
import com.artipie.http.auth.Authentication;
import com.artipie.http.auth.BasicAuthSlice;
import com.artipie.http.auth.Permission;
import com.artipie.http.auth.Permissions;
import com.artipie.http.headers.Accept;
import com.artipie.http.headers.ContentType;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.rs.RsStatus;
import com.artipie.http.rs.RsWithStatus;
import com.artipie.http.rt.ByMethodsRule;
import com.artipie.http.rt.RtRule;
import com.artipie.http.rt.RtRulePath;
import com.artipie.http.rt.SliceRoute;
import com.artipie.http.slice.HeadSlice;
import com.artipie.http.slice.SliceDelete;
import com.artipie.http.slice.SliceDownload;
import com.artipie.http.slice.SliceSimple;
import com.artipie.http.slice.SliceUpload;
import com.artipie.http.slice.SliceWithHeaders;
import com.artipie.vertx.VertxSliceServer;
import java.util.regex.Pattern;

/**
 * A {@link Slice} which servers binary files.
 *
 * @since 0.1
 * @todo #91:30min Test FileSlice when listing blobs by prefix in JSON.
 *  We previously introduced {@link BlobListJsonFormat}
 *  to list blobs in JSON from a prefix. We should now test that the type
 *  and value of response's content are correct when we make a request.
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 */
public final class FilesSlice extends Slice.Wrap {

    /**
     * HTML mime type.
     */
    public static final String HTML_TEXT = "text/html";

    /**
     * Plain text mime type.
     */
    public static final String PLAIN_TEXT = "text/plain";

    /**
     * Mime type of file.
     */
    private static final String OCTET_STREAM = "application/octet-stream";

    /**
     * JavaScript Object Notation mime type.
     */
    private static final String JSON = "application/json";

    /**
     * Ctor.
     * @param storage The storage. And default parameters for free access.
     */
    public FilesSlice(final Storage storage) {
        this(storage, Permissions.FREE, Authentication.ANONYMOUS);
    }

    /**
     * Ctor used by Artipie server which knows `Authentication` implementation.
     * @param storage The storage. And default parameters for free access.
     * @param perms Access permissions.
     * @param auth Auth details.
     */
    public FilesSlice(final Storage storage, final Permissions perms, final Authentication auth) {
        super(
            new SliceRoute(
                new RtRulePath(
                    new ByMethodsRule(RqMethod.HEAD),
                    new BasicAuthSlice(
                        new SliceWithHeaders(
                            new FileMetaSlice(
                                new HeadSlice(storage),
                                storage
                            ),
                            new Headers.From(new ContentType(FilesSlice.OCTET_STREAM))
                        ),
                        auth,
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    ByMethodsRule.Standard.GET,
                    new BasicAuthSlice(
                        new SliceRoute(
                            new RtRulePath(
                                new RtRule.ByHeader(
                                    Accept.NAME,
                                    Pattern.compile(FilesSlice.PLAIN_TEXT)
                                ),
                                new ListBlobsSlice(
                                    storage,
                                    BlobListFormat.Standard.TEXT,
                                    FilesSlice.PLAIN_TEXT
                                )
                            ),
                            new RtRulePath(
                                new RtRule.ByHeader(
                                    Accept.NAME,
                                    Pattern.compile(FilesSlice.JSON)
                                ),
                                new ListBlobsSlice(
                                    storage,
                                    BlobListFormat.Standard.JSON,
                                    FilesSlice.JSON
                                )
                            ),
                            new RtRulePath(
                                new RtRule.ByHeader(
                                    Accept.NAME,
                                    Pattern.compile(FilesSlice.HTML_TEXT)
                                ),
                                new ListBlobsSlice(
                                    storage,
                                    BlobListFormat.Standard.HTML,
                                    FilesSlice.HTML_TEXT
                                )
                            ),
                            new RtRulePath(
                                RtRule.FALLBACK,
                                new SliceWithHeaders(
                                    new FileMetaSlice(
                                        new SliceDownload(storage),
                                        storage
                                    ),
                                    new Headers.From(new ContentType(FilesSlice.OCTET_STREAM))
                                )
                            )
                        ),
                        auth,
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    ByMethodsRule.Standard.PUT,
                    new BasicAuthSlice(
                        new SliceUpload(storage),
                        auth,
                        new Permission.ByName(perms, Action.Standard.WRITE)
                    )
                ),
                new RtRulePath(
                    ByMethodsRule.Standard.DELETE,
                    new BasicAuthSlice(
                        new SliceDelete(storage),
                        auth,
                        new Permission.ByName(perms, Action.Standard.DELETE)
                    )
                ),
                new RtRulePath(
                    RtRule.FALLBACK,
                    new SliceSimple(new RsWithStatus(RsStatus.METHOD_NOT_ALLOWED))
                )
            )
        );
    }

    /**
     * Entry point.
     * @param args Command line args
     */
    public static void main(final String... args) {
        final int port = 8080;
        final VertxSliceServer server = new VertxSliceServer(
            new FilesSlice(new InMemoryStorage(), Permissions.FREE, Authentication.ANONYMOUS),
            port
        );
        server.start();
    }
}
