/*
* Copyright (c) IBM Corporation 2017. All Rights Reserved.
* Project name: java-async-util
* This project is licensed under the Apache License 2.0, see LICENSE.
*/

package com.ibm.asyncutil.iteration;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import com.ibm.asyncutil.iteration.AsyncIterator.End;
import com.ibm.asyncutil.locks.FairAsyncLock;
import com.ibm.asyncutil.util.Combinators;
import com.ibm.asyncutil.util.Either;
import com.ibm.asyncutil.util.StageSupport;

/**
 * Package private methods to use in {@link AsyncIterator}
 * 
 * @author Ravi Khadiwala
 * @author Renar Narubin
 */
class AsyncIterators {

  private AsyncIterators() {}

  static final EmptyAsyncIterator<?> EMPTY_ITERATOR = new EmptyAsyncIterator<>();

  private static class EmptyAsyncIterator<T> implements AsyncIterator<T> {

    @Override
    public CompletionStage<Either<End, T>> nextStage() {
      return End.endStage();
    }

    @Override
    public String toString() {
      return "EmptyAsyncIterator";
    }
  }

  @SuppressWarnings("unchecked")
  static <A, R> R finishContainer(final A accumulator, final Collector<?, A, R> collector) {
    // cast instead of applying the finishing function if the collector indicates the
    // finishing function is just identity
    return collector.characteristics().contains(Collector.Characteristics.IDENTITY_FINISH)
        ? ((R) accumulator)
        : collector.finisher().apply(accumulator);
  }

  /** Complete dest with whatever result (T or a Throwable) comes out of source */
  static <T> void listen(final CompletionStage<T> source, final CompletableFuture<T> dest) {
    source.whenComplete(
        (t, ex) -> {
          if (ex != null) {
            dest.completeExceptionally(ex);
          } else {
            dest.complete(t);
          }
        });
  }

  static <T> CompletionStage<T> convertSynchronousException(
      final Supplier<? extends CompletionStage<T>> supplier) {
    try {
      return supplier.get();
    } catch (final Throwable e) {
      return StageSupport.exceptionalStage(e);
    }
  }

  /** If both et and eu are right, then compute a new right either, otherwise just return left */
  static <T, U, V> Either<AsyncIterator.End, V> zipWith(
      final Either<AsyncIterator.End, T> et,
      final Either<AsyncIterator.End, U> eu,
      final BiFunction<? super T, ? super U, V> f) {
    return et.fold(end -> End.end(),
        t -> eu.fold(end -> End.end(), u -> Either.right(f.apply(t, u))));
  }

  static <T, U> AsyncIterator<U> thenApplyImpl(
      final AsyncIterator<T> it,
      final Function<? super T, ? extends U> f,
      final boolean synchronous,
      final Executor e) {
    assert !synchronous || e == null;
    return new AsyncIterator<U>() {
      @Override
      public CompletionStage<Either<End, U>> nextStage() {
        final CompletionStage<Either<End, T>> next = it.nextStage();

        return synchronous
            ? next.thenApply(this::eitherFunction)
            : e == null
                ? next.thenApplyAsync(this::eitherFunction)
                : next.thenApplyAsync(this::eitherFunction, e);
      }

      Either<End, U> eitherFunction(final Either<End, T> either) {
        return either.map(f);
      }

      @Override
      public CompletionStage<Void> close() {
        return it.close();
      }
    };
  }

  static <T, U> AsyncIterator<U> thenComposeImpl(
      final AsyncIterator<T> it,
      final Function<? super T, ? extends CompletionStage<U>> f,
      final boolean synchronous,
      final Executor e) {
    assert !synchronous || e == null;

    return new AsyncIterator<U>() {
      @Override
      public CompletionStage<Either<End, U>> nextStage() {
        final CompletionStage<Either<End, T>> nxt = it.nextStage();
        return synchronous
            ? nxt.thenCompose(this::eitherFunction)
            : e == null
                ? nxt.thenComposeAsync(this::eitherFunction)
                : nxt.thenComposeAsync(this::eitherFunction, e);
      }

      /*
       * if there's a value, apply f and wrap the result in an Either, otherwise just return end
       * marker
       */
      private CompletionStage<Either<End, U>> eitherFunction(final Either<End, T> either) {
        return either.fold(
            end -> End.endStage(),
            t -> f.apply(t).thenApply(Either::right));
      }

      @Override
      public CompletionStage<Void> close() {
        return it.close();
      }
    };
  }

  static class PartiallyEagerAsyncIterator<T, U> implements AsyncIterator<U> {
    private final AsyncIterator<T> backingIterator;
    private final int executeAhead;
    private final Function<U, CompletionStage<Void>> closeFn;
    private final Function<Either<End, T>, CompletionStage<Either<End, U>>> mappingFn;
    private final Queue<CompletionStage<Either<End, U>>> pendingResults;
    private final FairAsyncLock lock;
    private boolean closed;


    PartiallyEagerAsyncIterator(
        final AsyncIterator<T> backingIterator,
        final int executeAhead,
        final Function<Either<End, T>, CompletionStage<Either<End, U>>> mappingFn,
        final Function<U, CompletionStage<Void>> closeFn) {
      this.backingIterator = backingIterator;
      this.executeAhead = executeAhead;
      this.closeFn = closeFn == null
          ? u -> StageSupport.voidStage()
          : u -> AsyncIterators.convertSynchronousException(() -> closeFn.apply(u));
      this.mappingFn = mappingFn;
      this.pendingResults = new ArrayDeque<>(executeAhead);
      this.lock = new FairAsyncLock();
      this.closed = false;
    }

    /* return whether we need to keep filling */
    private CompletionStage<Either<End, T>> fillMore() {
      if (this.pendingResults.size() >= this.executeAhead) {
        // don't call nextStage, we already have enough stuff pending
        return End.endStage();
      } else {
        // keep filling up the ahead queue
        final CompletionStage<Either<End, T>> nxt =
            AsyncIterators.convertSynchronousException(this.backingIterator::nextStage);
        this.pendingResults.add(nxt.thenCompose(this.mappingFn));
        return nxt;
      }
    }

    /**
     * Get a nextStage either from the queue or the backing iterator and apply the mappingFn.
     *
     * @param listener stage is completed when the mapping function finishes
     * @return stage that is complete when any calls that this method made to nextStage are complete
     */
    private CompletionStage<Void> attachListener(final CompletableFuture<Either<End, U>> listener) {
      return StageSupport.tryComposeWith(this.lock.acquireLock(), token -> {
        if (this.closed) {
          final IllegalStateException ex =
              new IllegalStateException("nextStage called after async iterator was closed");
          listener.completeExceptionally(ex);
          throw ex;
        }

        final CompletionStage<Either<End, U>> poll = this.pendingResults.poll();
        if (poll == null) {

          // there was nothing in the queue, associate our returned future with a new
          // safeNextFuture call
          final CompletionStage<Either<End, T>> nxt =
              AsyncIterators.convertSynchronousException(this.backingIterator::nextStage);

          // don't bother adding it to the queue, because we are already listening on it
          AsyncIterators.listen(nxt.thenCompose(this.mappingFn), listener);

          return StageSupport.voided(nxt);
        } else {
          // let our future be tied to the first result that was in the queue
          AsyncIterators.listen(poll, listener);
          return StageSupport.voidStage();
        }
      });
    }

    @Override
    public CompletionStage<Either<End, U>> nextStage() {
      final CompletableFuture<Either<End, U>> listener = new CompletableFuture<>();
      final CompletionStage<Void> nextFinished = attachListener(listener);

      nextFinished.thenRun(() -> {
        AsyncTrampoline
            .asyncWhile(() -> StageSupport.tryComposeWith(this.lock.acquireLock(), token -> {
              if (this.closed) {
                return StageSupport.completedStage(false);
              }
              return fillMore()
                  .thenApply(Either::isRight)
                  // exceptional futures get added to the queue same as normal ones,
                  // we may continue filling
                  .exceptionally(e -> true);
            }));
      });

      return listener;
    }

    /*
     * wait for all pending results and then call close. epoch guarantees no more new results will
     * come in
     */
    @Override
    public CompletionStage<Void> close() {
      return StageSupport.tryComposeWith(this.lock.acquireLock(), token -> {
        this.closed = true;
        // call closeFn on all extra eagerly evaluated results
        final List<CompletionStage<Void>> closeFutures = this.pendingResults
            .stream()
            .map(f -> f.thenCompose(
                either -> either.fold(
                    end -> StageSupport.voidStage(),
                    this.closeFn)))
            .collect(Collectors.toList());

        // wait for all to complete
        final CompletionStage<Void> extraClose = Combinators.allOf(closeFutures);
        return StageSupport.thenComposeOrRecover(
            extraClose,
            (ig, extraCloseError) -> {
              // call close on the source iterator
              return StageSupport.thenComposeOrRecover(
                  AsyncIterators.convertSynchronousException(this.backingIterator::close),
                  (ig2, backingCloseError) -> {
                    if (extraCloseError != null) {
                      return StageSupport.<Void>exceptionalStage(extraCloseError);
                    } else if (backingCloseError != null) {
                      return StageSupport.<Void>exceptionalStage(backingCloseError);
                    }
                    return StageSupport.voidStage();
                  });
            });
      });
    }
  }

  private static class FailOnceAsyncIterator<T> implements AsyncIterator<T> {
    private Throwable exception;

    FailOnceAsyncIterator(final Throwable e) {
      this.exception = Objects.requireNonNull(e);
    }

    @Override
    public CompletionStage<Either<End, T>> nextStage() {
      if (this.exception != null) {
        final Throwable e = this.exception;
        this.exception = null;
        return StageSupport.exceptionalStage(e);
      } else {
        return End.endStage();
      }
    }
  }

  static class ConcatAsyncIterator<T> implements AsyncIterator<T> {
    private final Iterator<? extends AsyncIterator<T>> asyncIterators;
    private AsyncIterator<T> current;
  
    public ConcatAsyncIterator(final Iterator<? extends AsyncIterator<T>> asyncIterators) {
      assert asyncIterators.hasNext();
      this.asyncIterators = asyncIterators;
      this.current = asyncIterators.next();
    }
  
    @Override
    public CompletionStage<Either<AsyncIterator.End, T>> nextStage() {
      return asyncWhileAsyncInitial(
          et -> !et.isRight() && this.asyncIterators.hasNext(),
          /*
           * when reaching the end of one iterator, it must be closed before opening a new one. if
           * the `close` future yields an error, an errorOnce iterator is concatenated with that
           * close's exception, so the poll on the ConcatIter would encounter this exception. By
           * using an errorOnce iter, the caller could choose to ignore the exception and attempt
           * iterating again, which will pop the next asyncIterator off the meta iterator
           */
          ot -> StageSupport.thenComposeOrRecover(
              convertSynchronousException(this.current::close),
              (t, throwable) -> {
                this.current =
                    throwable == null
                        ? this.asyncIterators.next()
                        : errorOnce(throwable);
                return this.current.nextStage();
              }),
          this.current.nextStage());
    }
  
    @Override
    public CompletionStage<Void> close() {
      return this.current.close();
    }
  
    @Override
    public String toString() {
      return "ConcatAsyncIter [current="
          + this.current
          + ", iter="
          + this.asyncIterators
          + "]";
    }
  }

  static <T> AsyncIterator<T> errorOnce(final Throwable ex) {
    return new FailOnceAsyncIterator<>(ex);
  }

  static <T> CompletionStage<T> asyncWhileAsyncInitial(
      final Predicate<T> shouldContinue,
      final Function<T, CompletionStage<T>> loopBody,
      final CompletionStage<T> initialValue) {
    return initialValue.thenCompose(t -> AsyncTrampoline.asyncWhile(shouldContinue, loopBody, t));
  }
}
