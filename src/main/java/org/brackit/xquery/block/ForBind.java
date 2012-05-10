/*
 * [New BSD License]
 * Copyright (c) 2011-2012, Brackit Project Team <info@brackit.org>  
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the Brackit Project Team nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.brackit.xquery.block;

import java.util.ArrayDeque;

import org.brackit.xquery.QueryContext;
import org.brackit.xquery.QueryException;
import org.brackit.xquery.Tuple;
import org.brackit.xquery.atomic.Int32;
import org.brackit.xquery.atomic.IntNumeric;
import org.brackit.xquery.util.forkjoin.Task;
import org.brackit.xquery.xdm.Expr;
import org.brackit.xquery.xdm.Item;
import org.brackit.xquery.xdm.Iter;
import org.brackit.xquery.xdm.Iter.Split;
import org.brackit.xquery.xdm.Sequence;

/**
 * 
 * @author Sebastian Baechle
 * 
 */
public class ForBind implements Block {

	final Expr expr;
	final boolean allowingEmpty;
	boolean bindVar = true;
	boolean bindPos = false;

	public ForBind(Expr bind, boolean allowingEmpty) {
		this.expr = bind;
		this.allowingEmpty = allowingEmpty;
	}

	@Override
	public int outputWidth(int initSize) {
		return initSize + ((bindVar) ? 1 : 0) + ((bindPos) ? 1 : 0);
	}

	@Override
	public Sink create(QueryContext ctx, Sink sink) throws QueryException {
		return new ForBindSink(ctx, sink);
	}

	public void bindVariable(boolean bindVariable) {
		this.bindVar = bindVariable;
	}

	public void bindPosition(boolean bindPos) {
		this.bindPos = bindPos;
	}

	private class ForBindTask extends Task {
		final Tuple t;
		Iter it;
		Sink sink;
		IntNumeric pos = (bindPos) ? Int32.ZERO : null;

		public ForBindTask(Sink sink, Tuple t, Iter it) {
			this.sink = sink;
			this.t = t;
			this.it = it;
		}

		@Override
		public void compute() throws QueryException {
			Split split = it.split(100, 100);
			if (split.tail == null) {
				process(split.head);
			} else if (!split.serial) {
				ForBindTask task1 = new ForBindTask(sink, t, split.head);
				ForBindTask task2 = new ForBindTask(sink.fork(), t, split.tail);
				task2.fork();
				task1.compute();
				task2.join();
			} else {
				ArrayDeque<Task> queue = new ArrayDeque<Task>();
				while (true) {
					ForBindTask task = new ForBindTask(sink, t, split.head);
					if (split.tail != null) {
						sink = sink.fork();
					}
					FJControl.POOL.dispatch(task);
					queue.add(task);
					if (split.tail == null) {
						break;
					}
					if (queue.size() == 6) {
						queue.poll().join();
					}
					split = split.tail.split(100, 100);
				}
				for (Task t = queue.poll(); t != null; t = queue.poll()) {
					t.join();
				}
			}
		}

		private void process(Iter it) throws QueryException {
			sink.begin();
			try {
				Item i;
				int size = bufSize();
				Tuple[] buf = new Tuple[size];
				int len = 0;
				while ((i = it.next()) != null) {
					buf[len++] = i;
					if (len == size) {
						emit(buf, len);
						buf = new Tuple[size];
						len = 0;
					}
				}
				if (len > 0) {
					emit(buf, len);
				}
			} catch (QueryException e) {
				sink.fail();
				throw e;
			} finally {
				it.close();
			}
			sink.end();
		}

		private Tuple emit(Tuple t, Sequence item) throws QueryException {
			if (bindVar) {
				if (bindPos) {
					return t.concat(new Sequence[] { item,
							(item != null) ? (pos = pos.inc()) : pos });
				} else {
					return t.concat(item);
				}
			} else if (bindPos) {
				return t.concat((item != null) ? (pos = pos.inc()) : pos);
			} else {
				return t;
			}
		}

		private void emit(Tuple[] buf, int len) throws QueryException {
			for (int i = 0; i < len; i++) {
				buf[i] = emit(t, (Sequence) buf[i]);
			}
			sink.output(buf, len);
		}

		private int bufSize() {
			// TODO
			return 200;
		}
	}

	private class OutputTask extends Task {
		private final QueryContext ctx;
		private final Tuple[] buf;
		private final int start;
		private final int end;
		private Sink sink;

		public OutputTask(QueryContext ctx, Sink sink, Tuple[] buf, int start,
				int end) {
			this.ctx = ctx;
			this.sink = sink;
			this.buf = buf;
			this.start = start;
			this.end = end;
		}

		@Override
		public void compute() throws QueryException {
			if (end - start > 50) {
				int mid = start + ((end - start) / 2);
				OutputTask a = new OutputTask(ctx, sink.fork(), buf, mid, end);
				OutputTask b = new OutputTask(ctx, sink, buf, start, mid);
				a.fork();
				b.compute();
				a.join();
			} else {
				for (int i = start; i < end; i++) {
					Sequence s = expr.evaluate(ctx, buf[i]);
					if (s != null) {
						Sink ss = sink;
						sink = sink.fork();
						ForBindTask t = new ForBindTask(ss, buf[i], s.iterate());
						t.compute();
					}
				}
				sink.begin();
				sink.end();
			}
		}
	}

	private class ForBindSink extends FJControl implements Sink {
		Sink s;
		final QueryContext ctx;

		private ForBindSink(QueryContext ctx, Sink s) {
			this.ctx = ctx;
			this.s = s;
		}

		@Override
		public void output(Tuple[] t, int len) throws QueryException {
			// fork sink for future output calls
			Sink ss = s;
			s = s.fork();
			OutputTask task = new OutputTask(ctx, ss, t, 0, len);
			task.compute();
		}

		@Override
		public Sink fork() {
			return new ForBindSink(ctx, s.fork());
		}

		@Override
		public Sink partition(Sink stopAt) {
			return new ForBindSink(ctx, s.partition(stopAt));
		}

		@Override
		public void fail() throws QueryException {
			s.begin();
			s.fail();
		}

		@Override
		public void begin() throws QueryException {
			// do nothing
		}

		@Override
		public void end() throws QueryException {
			s.begin();
			s.end();
		}
	}
}