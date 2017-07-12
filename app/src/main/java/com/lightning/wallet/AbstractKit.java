package com.lightning.wallet;

import org.bitcoinj.core.*;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.ListenableFuture;
import org.bitcoinj.core.listeners.PeerDataEventListener;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Futures;
import org.bitcoinj.store.SPVBlockStore;
import java.util.concurrent.Executor;
import org.bitcoinj.wallet.Wallet;
import javax.annotation.Nullable;


public abstract class AbstractKit extends AbstractIdleService {
    public static final String CURRENCY = "fiatCurrencyOfChoice";
    public static final String ERROR_REPORT = "errorReport";

    // Bitcoin wallet core pieces
    public volatile BlockChain blockChain;
    public volatile PeerGroup peerGroup;
    public volatile SPVBlockStore store;
    public volatile Wallet wallet;

    public void startDownload(final PeerDataEventListener listener) {

        FutureCallback futureListener = new FutureCallback() {
            @Override public void onSuccess(@Nullable Object res) {
                peerGroup.startBlockChainDownload(listener);
            }

            @Override public void onFailure(@Nullable Throwable err) {
                throw new RuntimeException(err);
            }
        };

        ListenableFuture future = peerGroup.startAsync();
        Executor executor = MoreExecutors.directExecutor();
        Futures.addCallback(future, futureListener, executor);
    }
}