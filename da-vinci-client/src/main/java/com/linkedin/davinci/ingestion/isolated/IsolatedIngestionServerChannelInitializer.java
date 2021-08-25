package com.linkedin.davinci.ingestion.isolated;

import com.linkedin.ddsstorage.router.lnkd.netty4.SSLInitializer;
import com.linkedin.security.ssl.access.control.SSLEngineComponentFactory;
import com.linkedin.venice.listener.VerifySslHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import java.util.Optional;


public class IsolatedIngestionServerChannelInitializer extends ChannelInitializer<SocketChannel> {
  private final IsolatedIngestionServer isolatedIngestionServer;
  private final Optional<SSLEngineComponentFactory> sslFactory;
  private final VerifySslHandler verifySslHandler = new VerifySslHandler();

  public IsolatedIngestionServerChannelInitializer(IsolatedIngestionServer isolatedIngestionServer, Optional<SSLEngineComponentFactory> sslFactory) {
    this.sslFactory = sslFactory;
    this.isolatedIngestionServer = isolatedIngestionServer;
  }

  @Override
  protected void initChannel(SocketChannel ch) {
    sslFactory.ifPresent(
        sslEngineComponentFactory -> ch.pipeline().addLast(new SSLInitializer(sslEngineComponentFactory)));
    ch.pipeline().addLast(new HttpRequestDecoder());
    ch.pipeline().addLast(new HttpObjectAggregator(1024 * 1024));
    ch.pipeline().addLast(new HttpResponseEncoder());
    if (sslFactory.isPresent()) {
      ch.pipeline().addLast(verifySslHandler);
    }
    ch.pipeline().addLast(new IsolatedIngestionServerHandler(isolatedIngestionServer));
  }
}