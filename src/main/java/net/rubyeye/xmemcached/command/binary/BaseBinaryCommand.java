package net.rubyeye.xmemcached.command.binary;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;

import net.rubyeye.xmemcached.buffer.BufferAllocator;
import net.rubyeye.xmemcached.command.CommandType;
import net.rubyeye.xmemcached.command.StoreCommand;
import net.rubyeye.xmemcached.exception.MemcachedDecodeException;
import net.rubyeye.xmemcached.exception.UnknownCommandException;
import net.rubyeye.xmemcached.impl.MemcachedTCPSession;
import net.rubyeye.xmemcached.transcoders.CachedData;
import net.rubyeye.xmemcached.transcoders.Transcoder;
import net.rubyeye.xmemcached.utils.ByteUtils;

/**
 * Base binary protocol implementation
 * 
 * @author dennis
 * 
 */
@SuppressWarnings("unchecked")
public class BaseBinaryCommand extends StoreCommand {
	protected OpCode opCode;
	protected BinaryDecodeStatus decodeStatus = BinaryDecodeStatus.NONE;
	protected int responseKeyLength, responseExtrasLength,
			responseTotalBodyLength;

	public BaseBinaryCommand(String key, byte[] keyBytes, CommandType cmdType,
			CountDownLatch latch, int exp, long cas, Object value,
			boolean noreply, Transcoder transcoder) {
		super(key, keyBytes, cmdType, latch, exp, cas, value, noreply,
				transcoder);
		switch (cmdType) {
		case SET:
			this.opCode = OpCode.SET;
			break;
		case REPLACE:
			this.opCode = OpCode.REPLACE;
			break;
		case ADD:
			this.opCode = OpCode.ADD;
			break;

		}
	}

	@Override
	public final boolean decode(MemcachedTCPSession session, ByteBuffer buffer) {
		while (true) {
			LABEL: switch (this.decodeStatus) {
			case NONE:
				if (buffer.remaining() < 24) {
					return false;
				} else {
					this.decodeStatus = BinaryDecodeStatus.READ_HEADER;
				}
				continue;
			case READ_HEADER:
				readHeader(buffer);
				continue;
			case READ_EXTRAS:
				if (readExtras(buffer, this.responseExtrasLength)) {
					this.decodeStatus = BinaryDecodeStatus.READ_KEY;
					continue;
				} else {
					return false;
				}
			case READ_KEY:
				if (readKey(buffer, this.responseKeyLength)) {
					this.decodeStatus = BinaryDecodeStatus.READ_VALUE;
					continue;
				} else {
					return false;
				}
			case READ_VALUE:
				if (readValue(buffer, this.responseTotalBodyLength,
						this.responseKeyLength, this.responseExtrasLength)) {
					this.decodeStatus = BinaryDecodeStatus.DONE;
					continue;
				} else {
					return false;
				}
			case DONE:
				if (finish()) {
					return true;
				} else {
					this.decodeStatus = BinaryDecodeStatus.NONE;
					break LABEL;
				}
			}
		}
	}

	protected boolean finish() {
		countDownLatch();
		return true;
	}

	protected void readHeader(ByteBuffer buffer) {
		readMagicNumber(buffer);
		readOpCode(buffer);
		this.responseKeyLength = readKeyLength(buffer);
		this.responseExtrasLength = readExtrasLength(buffer);
		readStatus(buffer);
		this.responseTotalBodyLength = readBodyLength(buffer);
		readOpaque(buffer);
		readCAS(buffer);
		this.decodeStatus = BinaryDecodeStatus.READ_EXTRAS;
	}

	protected void readOpaque(ByteBuffer buffer) {
		ByteUtils.stepBuffer(buffer, 4);
	}

	protected long readCAS(ByteBuffer buffer) {
		ByteUtils.stepBuffer(buffer, 8);
		return 0;
	}

	protected boolean readKey(ByteBuffer buffer, int keyLength) {
		return true;
	}

	protected boolean readValue(ByteBuffer buffer, int bodyLength,
			int keyLength, int extrasLength) {
		if (!ByteUtils.stepBuffer(buffer, bodyLength)) {
			throw new MemcachedDecodeException(
					"Store command decode error,buffer remaining <"
							+ (12 + bodyLength));
		}
		return true;
	}

	protected boolean readExtras(ByteBuffer buffer, int extrasLength) {
		return true;
	}

	private int readBodyLength(ByteBuffer buffer) {
		return buffer.getInt();
	}

	protected void readStatus(ByteBuffer buffer) {
		ResponseStatus responseStatus = ResponseStatus.parseShort(buffer
				.getShort());

		if (responseStatus == ResponseStatus.NO_ERROR) {
			setResult(true);
		} else if (responseStatus == ResponseStatus.UNKNOWN_COMMAND) {
			setException(new UnknownCommandException());
		} else {
			setResult(false);
		}
	}

	private int readKeyLength(ByteBuffer buffer) {
		return buffer.getShort();
	}

	private int readExtrasLength(ByteBuffer buffer) {
		return buffer.getShort();
	}

	private void readOpCode(ByteBuffer buffer) {
		byte op = buffer.get();

		if (op != this.opCode.fieldValue()) {
			throw new MemcachedDecodeException("Not a proper "
					+ this.opCode.name() + " response");
		}
	}

	private void readMagicNumber(ByteBuffer buffer) {
		byte magic = buffer.get();

		if (magic != RESPONSE_MAGIC_NUMBER) {
			throw new MemcachedDecodeException("Not a proper response");
		}
	}

	static final byte EXTRAS_LENGTH = (byte) 8;

	@Override
	public final void encode(BufferAllocator bufferAllocator) {
		CachedData data = null;
		if (this.transcoder != null) {
			data = this.transcoder.encode(this.value);
		}
		// header+key+value+extras
		int length = 16 + 8 + getKeyLength() + getValueLength(data) + 8;

		this.ioBuffer = bufferAllocator.allocate(length);
		fillHeader(data);
		fillExtras(data);
		fillKey();
		fillValue(data);

		this.ioBuffer.flip();

	}

	protected void fillValue(final CachedData data) {
		this.ioBuffer.put(data.getData());
	}

	protected void fillKey() {
		this.ioBuffer.put(this.keyBytes);
	}

	protected void fillExtras(final CachedData data) {
		this.ioBuffer.putInt(data.getFlag());
		this.ioBuffer.putInt(this.expTime);
	}

	protected final void fillHeader(final CachedData data) {
		this.ioBuffer.put(REQUEST_MAGIC_NUMBER);
		this.ioBuffer.put(this.opCode.fieldValue());
		this.ioBuffer.putShort((short) getKeyLength());
		this.ioBuffer.put(getExtrasLength());
		// Data type
		this.ioBuffer.put((byte) 0);
		// Reserved
		this.ioBuffer.putShort((short) 0);

		this.ioBuffer.putInt(getExtrasLength() + getKeyLength()
				+ getValueLength(data));
		// Opaque
		this.ioBuffer.putInt(0);
		// CAS
		this.ioBuffer.putLong(0L);
	}

	protected int getValueLength(final CachedData data) {
		return data.getData().length;
	}

	protected int getKeyLength() {
		return this.keyBytes.length;
	}

	protected byte getExtrasLength() {
		return EXTRAS_LENGTH;
	}

}