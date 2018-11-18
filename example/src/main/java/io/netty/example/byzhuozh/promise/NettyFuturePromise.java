package io.netty.example.byzhuozh.promise;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * @author zhuozh
 * @version $Id: NettyFuturePromise.java, v 0.1 2018/11/18 1:01 zhuozh Exp $
 */
public class NettyFuturePromise {
    static Logger logger = LoggerFactory.getLogger(NettyFuturePromise.class);

    public static void main(String[] args) {

        NettyFuturePromise.primiseDemo();

    }

    public static void primiseDemo() {
        try {
            NettyFuturePromise test = new NettyFuturePromise();
            Promise<String> promise = test.search("Netty In Action");

            promise.addListener(new GenericFutureListener<Future<? super String>>() {
                @Override
                public void operationComplete(Future<? super String> future) throws Exception {
                    logger.info("Listener 1, make a notifice to Hong,price is " + future.get());
                }
            });

            promise.addListener(new GenericFutureListener<Future<? super String>>() {
                @Override
                public void operationComplete(Future<? super String> future) throws Exception {
                    logger.info("Listener 2, send a email to Hong,price is " + future.get());
                }
            });

            String result = promise.get();
            logger.info("price is " + result);

        } catch (InterruptedException e) {
            logger.error("InterruptedException [{}]", e);
        } catch (ExecutionException e) {
            logger.error("ExecutionException [{}]", e);
        }

    }

    private Promise<String> search(final String prod) {
        NioEventLoopGroup loop = new NioEventLoopGroup();
        // 创建一个DefaultPromise并返回
        final DefaultPromise<String> promise = new DefaultPromise<String>(loop.next());
        loop.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    logger.info(String.format(">>> search price of %s from internet!",prod));
                    Thread.sleep(5000);
                    promise.setSuccess("$99.99");// 等待5S后设置future为成功，
//                    promise.setFailure(new NullPointerException()); //当然，也可以设置失败
                } catch (InterruptedException e) {
                    logger.info("search》》》InterruptedException [{}]", e);
                }
            }
        },0, TimeUnit.SECONDS);

        return promise;
    }


}
