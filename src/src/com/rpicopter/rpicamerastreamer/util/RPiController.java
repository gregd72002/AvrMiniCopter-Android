package com.rpicopter.rpicamerastreamer.util;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.SocketAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;

import com.rpicopter.rpicamerastreamer.Callback;

import android.util.Log;

public class RPiController {
	public String error;
	public int status = 0;
	private Callback context;
	private Timer pingtimer;
	private byte [] pingbuffer,databuffer;
	private byte [] datarbuffer;
	private ByteBuffer pingbbuffer,databbuffer;
	private InetAddress rpi;
	private int rpi_port;
	private Thread readerloop;
	private Timer writerloop;
	private DatagramChannel channel;
	private long pingsent;
	private int seq, recv_seq;
	private int [] response;
	private int sampleSize = 10;
	private int latency;
	private int altitude;
	private boolean isRunning;
	
	public int[] yprt;
	private int mt,mv;
	private boolean altHold;
	
	public RPiController(Callback c, byte []rpi_ip, int rpi_port) {
		this.rpi_port = rpi_port;
		context = c;
		altitude = 0;
		response = new int[sampleSize];
		isRunning = false;
		yprt = new int[4];
		altHold = false;
		try {
			status = 0;
			rpi = InetAddress.getByAddress(rpi_ip);
			
			pingbuffer = new byte[5];
			pingbbuffer = ByteBuffer.wrap(pingbuffer);
		    
		    databuffer = new byte[20];
		    databbuffer = ByteBuffer.wrap(databuffer);
		    datarbuffer = new byte[64];

		        
		} catch (Exception ex) {
			error = ex.toString();
			status = -1;
			context.notify(0, error);
		}
	}
    
	private void updatePingResponse(int seq, int lat) {
		latency = lat;
		response[seq % sampleSize] = lat;
		context.notify(1,null);
	}
	
	
	public float getRate() {
		float currentSuccessRate = 0;
		int c = 0;
		for (int i=0;i<sampleSize;i++) {
			currentSuccessRate += response[i]>0?1:0;
			if (response[i]!=0) c++;
		}
		currentSuccessRate /= c;
		return currentSuccessRate;
	}
	
	public int getLatency() {
		return latency;
	}
	
	public int getAltitude() {
		return altitude;
	}
	
    private void ping() {
    	if (recv_seq != (seq-1)) { //we did not receive previous response
    		updatePingResponse((seq-1),-1);
    	}
    	pingbbuffer.clear();
	    pingbbuffer.put(0,(byte)2);
	    pingbbuffer.putInt(1,seq);
    	try {
    		pingsent = System.currentTimeMillis();
    		channel.write(pingbbuffer);
		} catch (IOException e) {
			//Log.d("PING","PING SEND ERROR "+e);
		}
    	seq++;
    }
    
    private void readping(int c,ByteBuffer b) {    
        recv_seq = b.getInt(c);
        int expected = seq-1;
        long pingrecv = System.currentTimeMillis();
		int lat = (int) (pingrecv-pingsent) + Math.abs(expected-recv_seq)*1000;
		updatePingResponse(recv_seq,lat);
		
		//Log.d("PING","PING "+recv_seq+" "+expected+" "+lat+"ms");

    }
    
	private void loop() {
        try {
        	Log.d("DATA","DATA: starting");
            channel = DatagramChannel.open();
            InetSocketAddress isa = new InetSocketAddress(rpi,rpi_port);
            channel.connect(isa);
            channel.configureBlocking(false);
            Selector dataselector = Selector.open();
            channel.register(dataselector, SelectionKey.OP_READ);
            
    		pingtimer = new Timer();
    		pingtimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                	ping(); //ping writer
                }
            }, 0, 1000);//put here time 1000 milliseconds=1 second
    		
    		writerloop = new Timer();
    		writerloop.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                	writedata();
                }
            }, 0, 50);//put here time 1000 milliseconds=1 second
    		
            while (isRunning) {
                try {
                    dataselector.select();
                    Iterator<SelectionKey> selectedKeys = dataselector.selectedKeys().iterator();
                    while (selectedKeys.hasNext()) {
                        try {
                            SelectionKey key = (SelectionKey) selectedKeys.next();
                            selectedKeys.remove();

                            if (!key.isValid()) {
                              continue;
                            }

                            if (key.isReadable()) {
                                readpacket();
                                key.interestOps(SelectionKey.OP_READ);
                            }
                        } catch (IOException e) {
                            Log.d("DATA","DATA: glitch, continuing... " +(e.getMessage()!=null?e.getMessage():""));
                        }
                    }
                } catch (IOException e) {
                	Log.d("DATA","DATA: glitch, continuing... " +(e.getMessage()!=null?e.getMessage():""));
                }
            }
        } catch (IOException e) {
        	Log.d("DATA","DATA: network error: " + (e.getMessage()!=null?e.getMessage():""));
        }	
        pingtimer.cancel();
        Log.d("DATA","DATA: stopping");
	}
	
    private void readpacket() throws IOException {
    	ByteBuffer bbuf = ByteBuffer.wrap(datarbuffer);
    	bbuf.clear();
    	int c,len = channel.read(bbuf);
        c = 0;
        while (c<len) {
        	int packet_type = bbuf.get(c) & 0xFF;
        	c++;
        	switch (packet_type) {
        	case 0: readdata(c,bbuf); c+=3; break;
        	case 1: readping(c,bbuf); c+=4; break;
        	default: Log.d("DATA","DATA unknown packet type: "+packet_type);
        	}
        }
        
    }

    private void readdata(int c,ByteBuffer b) {
    	int type = b.get(c) & 0xFF;
    	short value = b.getShort(c+1);
    	//Log.d("DATA","DATA recv t:"+type+" v:"+value);
    	switch (type) {
    		case 19: altitude = value; break;
    	}
    }
    
    private void writedata() {
    	databbuffer.clear();
    	addMsg(0,10,yprt[0]);
    	addMsg(4,11,yprt[1]);
    	addMsg(8,12,yprt[2]);
    	addMsg(12,13,yprt[3]);
    	addQueue(16);
    	try {
    		channel.write(databbuffer);
		} catch (IOException e) {
			Log.d("DATA","DATA WRITING ERROR "+e);
		}
    }
    
    private void addMsg(int c, int t, int v) {    	
	    databbuffer.put(c,(byte)1);
        databbuffer.put(c+1,(byte)t);
        databbuffer.putShort(c+2,(short)v);   
    }
    
    private void addQueue(int c) {
    	if (mt>=0) {
    		addMsg(c,mt,mv);
    		mt = -1; mv = -1;
    	} else addMsg(c,0,0);
    }
    
    private void queueMsg(int t, int v) {
    	mt = t;
    	mv = v;
    }

	public void start() {
		seq = 0;
		recv_seq = -1;
		latency = 0;
		for (int i=0;i<sampleSize;i++) response[i] = 0;
		
		isRunning = true;
		
		readerloop = new Thread(new Runnable(){
		    @Override
		    public void run() {
		    	loop();
		    }
		});
		readerloop.start();
	}
	
	private void _stop() {
		try {

		} catch (Exception ex) {
		}
	}
	
	public void stop() {
		Log.d("RPiController","RPiController stopping");
		new Thread(new Runnable(){
		    @Override
		    public void run() {
		    	_stop();
		    }
		}).start();
		isRunning = false;
		writerloop.cancel();
		readerloop.interrupt();
	}
	
	public void toggleAltHold() {
		altHold = !altHold;
		queueMsg(15,altHold?1:0);
	}
	
	public void exitAltHold() {
		queueMsg(15,0);
		altHold = false;
	}

	public void reset() {
		queueMsg(255,255);
	}
	
	public void incAltitude() {
		queueMsg(16,50);
	}
	
	public void decAltitude() {
		queueMsg(16,-50);
	}
	
}
