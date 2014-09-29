package org.opendaylight.controller.cup.provider;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.api.ReadWriteTransaction;
import org.opendaylight.controller.md.sal.binding.api.WriteTransaction;
import org.opendaylight.controller.md.sal.common.api.TransactionStatus;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.OptimisticLockFailedException;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.controller.sal.binding.api.NotificationProviderService;
import org.opendaylight.controller.sal.common.util.RpcErrors;
import org.opendaylight.controller.sal.common.util.Rpcs;
import org.opendaylight.yang.gen.v1.inocybe.rev141116.Cup;
import org.opendaylight.yang.gen.v1.inocybe.rev141116.Cup.CupStatus;
import org.opendaylight.yang.gen.v1.inocybe.rev141116.CupBuilder;
import org.opendaylight.yang.gen.v1.inocybe.rev141116.CupService;
import org.opendaylight.yang.gen.v1.inocybe.rev141116.DisplayString;
import org.opendaylight.yang.gen.v1.inocybe.rev141116.HeatCupInput;
import org.opendaylight.yang.gen.v1.inocybe.rev141116.NoMoreCupsBuilder;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.RpcError;
import org.opendaylight.yangtools.yang.common.RpcError.ErrorSeverity;
import org.opendaylight.yangtools.yang.common.RpcError.ErrorType;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

public class OpendaylightCup  implements CupService, AutoCloseable{
    //making this public because this unique ID is required later on in other classes.
    public static final InstanceIdentifier<Cup>  CUP_IID = InstanceIdentifier.builder(Cup.class).build();
    private static final Logger LOG = LoggerFactory.getLogger(OpendaylightCup.class);
    private static final DisplayString CUP_MANUFACTURER = new DisplayString("Opendaylight");
    private static final DisplayString CUP_MODEL_NUMBER = new DisplayString("Model 1 - Binding Aware");

    private NotificationProviderService notificationProvider;
    private DataBroker dataProvider;
    private final ExecutorService executor;

    private final AtomicLong amountOfCupsInStock = new AtomicLong( 100 );

    private final AtomicLong cupTemperature = new AtomicLong( 1000 );

    private final AtomicLong cupsMade = new AtomicLong(0);

    // The following holds the Future for the current heat cup task.
    // This is used to cancel the current toast.
    private final AtomicReference<Future<?>> currentHeatCupTask = new AtomicReference<>();

    public OpendaylightCup(){
        executor = Executors.newFixedThreadPool(1);
    }

    private Cup buildCup( CupStatus status ) {
        // note - we are simulating a device whose manufacture and model are
        // fixed (embedded) into the hardware.
        // This is why the manufacture and model number are hardcoded.
        return new CupBuilder().setCupManufacturer( CUP_MANUFACTURER )
                                   .setCupModelNumber( CUP_MODEL_NUMBER )
                                   .setCupStatus( status )
                                   .build();
    }

    /**
     * Set the dataBroker
     * @param salDataProvider
     */
    public void setDataProvider( final DataBroker salDataProvider ) {
        this.dataProvider = salDataProvider;
        setCupStatusCold( null );
    }

    /**
     * Set the cup status in the MD-SAL tree using the
     * MD-SAL data broker. This is a write only transaction.
     * @param resultCallback
     */
    private void setCupStatusCold(final Function<Boolean, Void> resultCallback) {

        WriteTransaction tx = dataProvider.newWriteOnlyTransaction();
        tx.put( LogicalDatastoreType.OPERATIONAL, CUP_IID,
                buildCup(CupStatus.Cold));

        Futures.addCallback(tx.submit(), new FutureCallback<Void>() {
            @Override
            public void onSuccess(final Void result) {
                notifyCallback(true);
            }

            @Override
            public void onFailure(final Throwable t) {
                // We shouldn't get an OptimisticLockFailedException (or any ex)
                // as no
                // other component should be updating the operational state.
                LOG.error("Failed to update cup status", t);

                notifyCallback(false);
            }

            void notifyCallback(final boolean result) {
                if (resultCallback != null) {
                    resultCallback.apply(result);
                }
            }
        });
    }

    /**
     * The close method implementation of autocloseable.
     */
    @Override
	public void close() throws Exception {
        executor.shutdown();
        
        if (dataProvider != null) {
            WriteTransaction tx = dataProvider.newWriteOnlyTransaction();
            tx.delete(LogicalDatastoreType.OPERATIONAL,CUP_IID);
            Futures.addCallback( tx.submit(), new FutureCallback<Void>() {
                @Override
                public void onSuccess( final Void result ) {
                    LOG.debug( "Delete cup commit result: " + result );
                }

                @Override
                public void onFailure( final Throwable t ) {
                    LOG.error( "Delete of Cup failed", t );
                }
            } );
        }
	}

    /**
     * Uses the yangtools.yang.common.RpcResultBuilder to
     * return a cancel cup Future.
     */
    @Override
    public Future<RpcResult<Void>> cancelCup() {

        Future<?> current = currentHeatCupTask.getAndSet(null);
        if (current != null){
            current.cancel(true);
        }
        return Futures.immediateFuture(RpcResultBuilder.<Void> success().build());
    }

    /**
     * Use the checkStatusAndHeatCup method to create a cup Future by
     * reading the CupStatus and, if currently Cold, try to write the status to Heating.
     * if that succeeds, then we essentially have an exclusive lock and can proceed
     * to make the cup.
     */
    @Override
    public Future<RpcResult<Void>> heatCup(HeatCupInput input) {
        final SettableFuture<RpcResult<Void>> futureResult = SettableFuture.create();
        
        checkStatusAndHeatCup( input, futureResult, 2 );
   
        return futureResult;
    }
    
    /**
     * Read the CupStatus and, if currently Cold, try to write the status to Heating.
     * If that succeeds, then we essentially have an exclusive lock and can proceed
     * to make the cup.
     * @param input
     * @param futureResult
     * @param tries
     */
    private void checkStatusAndHeatCup(final HeatCupInput input,
                                       final SettableFuture<RpcResult<Void>> futureResult,
                                       final int tries) {

        // Read the CupStatus and, if currently Cold, try to write the status to Heating.
        // If that succeeds, then we essentially have an exclusive lock and can proceed
        // to make the cup.
        /**
         * We create a ReadWriteTransaction by using the databroker.
         * Then, we read the status of the cup with getCupStatus() using the
         * databroker again. Once we have the status, we analyze it and
         * then databroker submit function is called to effectively change 
         * the cup status.
         * 
         * This all affects the MD-SAL tree, more specifically the part of the
         * tree that contain the cup (the nodes).
         */
        final ReadWriteTransaction tx = dataProvider.newReadWriteTransaction();
        ListenableFuture<Optional<Cup>> readFuture =
                                          tx.read( LogicalDatastoreType.OPERATIONAL, CUP_IID );

        final ListenableFuture<Void> commitFuture =
            Futures.transform( readFuture, new AsyncFunction<Optional<Cup>,Void>() {

                @Override
                public ListenableFuture<Void> apply(
                        final Optional<Cup> cupData ) throws Exception {

                    CupStatus toasterStatus = CupStatus.Cold;
                    if( cupData.isPresent() ) {
                        toasterStatus = cupData.get().getCupStatus();
                    }

                    LOG.debug( "Read toaster status: {}", toasterStatus );

                    if( toasterStatus == CupStatus.Cold ) {

                        if( outOfCups() ) {
                            LOG.debug( "No more cups" );

                            return Futures.immediateFailedCheckedFuture(
                                    new TransactionCommitFailedException( "", makeNoMoreCupsError() ) );
                        }

                        LOG.debug( "Setting Toaster status to Down" );

                        // We're not currently making toast - try to update the status to Down
                        // to indicate we're going to make toast. This acts as a lock to prevent
                        // concurrent toasting.
                        tx.put( LogicalDatastoreType.OPERATIONAL, CUP_IID,
                                buildCup( CupStatus.Heating ) );
                        return tx.submit();
                    }

                    LOG.debug( "Oops - already making a cup!" );

                    // Return an error since we are already making cup. This will get
                    // propagated to the commitFuture below which will interpret the null
                    // TransactionStatus in the RpcResult as an error condition.
                    return Futures.immediateFailedCheckedFuture(
                            new TransactionCommitFailedException( "", makeCupInUseError() ) );
                }
        } );

        Futures.addCallback( commitFuture, new FutureCallback<Void>() {
            @Override
            public void onSuccess( final Void result ) {
                // OK to make cup
                currentHeatCupTask.set( executor.submit( new HeatCupTask( input, futureResult ) ) );
            }

            @Override
            public void onFailure( final Throwable ex ) {
                if( ex instanceof OptimisticLockFailedException ) {

                    // Another thread is likely trying to make toast simultaneously and updated the
                    // status before us. Try reading the status again - if another make toast is
                    // now in progress, we should get ToasterStatus.Down and fail.

                    if( ( tries - 1 ) > 0 ) {
                        LOG.debug( "Got OptimisticLockFailedException - trying again" );

                        checkStatusAndHeatCup( input, futureResult, tries - 1 );
                    }
                    else {
                        futureResult.set( RpcResultBuilder.<Void> failed()
                                .withError( ErrorType.APPLICATION, ex.getMessage() ).build() );
                    }

                } else {

                    LOG.debug( "Failed to commit Toaster status", ex );

                    // Probably already making toast.
                    futureResult.set( RpcResultBuilder.<Void> failed()
                            .withRpcErrors( ((TransactionCommitFailedException)ex).getErrorList() )
                            .build() );
                }
            }
        } );
    }// CheckStatusAndMakeCup

    /**
     * This is where the cup is heated. The callable method
     * is running as a thread but returns a value. In this
     * case, the HeatCupTask returns null. The function heats
     * the cup, when the cup is at the desired temprature, the
     * function returns null and the tea is ready to be brewed.
     * 
     * TODO Englishmen drinks the cup, then ths method
     * sets the cup back to cold. The cup is automatically
     * filled with cold water readyt o be heated in the
     * microwave.
     */
    private class HeatCupTask implements Callable<Void> {

        final HeatCupInput cupRequest;
        final SettableFuture<RpcResult<Void>> futureResult;

        public HeatCupTask( final HeatCupInput cupRequest,
                            final SettableFuture<RpcResult<Void>> futureResult ) {
            this.cupRequest = cupRequest;
            this.futureResult = futureResult;
        }

        @Override
        public Void call() {
            try
            {
                // make cup just sleeps for n seconds per 10 degrees level.
                long cupTemperature = OpendaylightCup.this.cupTemperature.get();
                //Thread.sleep(cupTemperature * cupRequest.getCupTemperature());
                Thread.sleep(cupTemperature * (10)*cupRequest.getCupTemperature());
                System.out.println("Thread.sleep:"+(cupTemperature * (10)*cupRequest.getCupTemperature()));
            }
            catch( InterruptedException e ) {
                LOG.info( "Interrupted while making the toast" );
            }

            cupsMade.incrementAndGet();

            amountOfCupsInStock.getAndDecrement();
            if( outOfCups() ) {
                LOG.info( "Toaster is out of bread!" );

                notificationProvider.publish( new NoMoreCupsBuilder().build() );
            }

            // Set the Toaster status back to up - this essentially releases the toasting lock.
            // We can't clear the current toast task nor set the Future result until the
            // update has been committed so we pass a callback to be notified on completion.

            setCupStatusCold( new Function<Boolean,Void>() {
                @Override
                public Void apply( final Boolean result ) {

                    currentHeatCupTask.set( null );

                    LOG.debug("Cup ready");

                    futureResult.set( RpcResultBuilder.<Void>success().build() );

                    return null;
                }
            } );

            return null;
        }
    }

    /**
     * 
     * @return true if there are no more cups, false otherwise.
     */
    private boolean outOfCups()
    {
        return amountOfCupsInStock.get() == 0;
    }

    /**
     * 
     * @return The RPC error message in case the RPC service
     * is not available.
     */
    private RpcError makeNoMoreCupsError() {
        return RpcResultBuilder.newError( ErrorType.APPLICATION, "resource-denied",
                "No more cups", "out-of-stock", null, null );
    }

    /**
     * 
     * @return The RPC error in use.
     */
    private RpcError makeCupInUseError() {
        return RpcResultBuilder.newWarning( ErrorType.APPLICATION, "in-use",
                "Cup is busy (in-use)", null, null, null );
    }

}
