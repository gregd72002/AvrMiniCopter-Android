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
	private int i;
	private Timer pingtimer;
	private byte [] pingbuffer,databuffer;
	private byte [] datarbuffer;
	private ByteBuffer pingbbuffer,databbuffer;
	private InetAddress rpi;
	private int rpi_port;
	private Thread readerloop;
	private Timer writerloop;
	private int writecounter;
	private DatagramChannel channel;
	private long pingsent;
	private short seq, recv_seq,expected;
	private int [] response;
	private int sampleSize = 10; //for ping ratio
	private int latency;
	private int altitude;
	private int rssi;
	private int linkSpeed;
	private boolean isRunning;
	private int avrstatus;
	private int avrcode;
	
	private boolean hasController;
	public int[] yprt;
	private int mt,mv;
	private boolean altHold;
	private int flyMode;
	
	private int MSG_SIZE = 4;
	
	public RPiController(Callback c, byte []rpi_ip, int rpi_port) {
		this.rpi_port = rpi_port;
		context = c;
		hasController = false;
		altitude = 0;
		avrstatus = 0;
		avrcode = 0;
		rssi = 0;
		linkSpeed = 0;
		response = new int[sampleSize];
		isRunning = false;
		yprt = new int[4];
		altHold = false;
		flyMode = 0;
		try {
			status = 0;
			rpi = InetAddress.getByAddress(rpi_ip);
			
			pingbuffer = new byte[MSG_SIZE];
			pingbbuffer = ByteBuffer.wrap(pingbuffer);
		    
		    databuffer = new byte[5*MSG_SIZE];
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
		response[Math.abs(seq) % sampleSize] = lat;
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
	
	public int getRSSI() {
		return rssi;
	}

	public int getLinkSpeed() {
		return linkSpeed;
	}	
	
	public int getAltitude() {
		return altitude;
	}
	
	public int getAVRStatus() {
		return avrstatus;
	}
	
	public int getAVRCode() {
		return avrcode;
	}
	
    private void ping() {
    	//Log.d("SPING","SPING "+seq+" "+recv_seq);
    	if (recv_seq != (seq-1)) { //we did not receive previous response
    		updatePingResponse((seq-1),-1);
    	}
    	pingbbuffer.clear();
	    pingbbuffer.put(0,(byte)2); //keep-alive-ping
	    pingbbuffer.putShort(1,(byte)0); //request ping
	    pingbbuffer.putShort(2,(short)seq); //seq number
    	try {
    		pingsent = System.currentTimeMillis();
    		channel.write(pingbbuffer);
		} catch (IOException e) {
			//Log.d("PING","PING SEND ERROR "+e);
		}
    	expected = seq;
    	seq++;
    	if (seq>32000) seq = -32000;  //there is discrepency in MAX short for Java and C so lets keep safe from the end
    }
    
    private void readping(int c,ByteBuffer b) {
        recv_seq = b.getShort(c);
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
    		
    		writecounter = 0;
    		writerloop = new Timer();
    		writerloop.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                	writedata();
                }
            }, 0, 50);//put here time 1000 milliseconds=1 second
    		
    		setLog(4);
    		
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
        	int control = bbuf.get(c);
        	int packet_type = bbuf.get(c+1);
        	
        	if (control==2) readping(c+2,bbuf); //other control values are not used at the moment
        	else readdata(control,c+1,bbuf);
        	/*
        	else switch (packet_type) {
        		case 14: readdata(c+2,bbuf); break;
        		default: Log.d("DATA","DATA unknown packet type: "+packet_type);
        	}
        	*/
        	c+=MSG_SIZE;
        }
        
    }

    private void readdata(int control,int c,ByteBuffer b) {
    	int type = b.get(c) & 0xFF;
    	short value = b.getShort(c+1);
    	//Log.d("DATA","DATA recv t:"+type+" v:"+value);
    	if (control==0 && type==19) altitude = value;
    	if (control==3 && type==249) rssi = value;
    	if (control==3 && type==248) linkSpeed = value;
    	if (control==0 && type==255) {
    		avrstatus = (value & 0xFF00) >> 8;
        	avrcode = (byte)(value & 0xFF);
    	}
    }
    
    private void writedata() {
    	writecounter++;
    	databbuffer.clear();
    	if (hasController) {
    		addMsg(0,10,yprt[0]);
    		addMsg(4,11,yprt[1]);
    		addMsg(8,12,yprt[2]);
    		addMsg(12,13,yprt[3]);
    		addQueue(16);
    	} else {
    		addQueue(0);
    	}
    
    	try {
    		channel.write(databbuffer);
		} catch (IOException e) {
			Log.d("DATA","DATA WRITING ERROR "+e);
		}
    }
    
    private void addMsg(int c, int t, int v) {    // c - offset
	    databbuffer.put(c,(byte)1); //keep-alive
        databbuffer.put(c+1,(byte)t); //type
        databbuffer.putShort(c+2,(short)v); //value  
    }
    
    private void addQueue(int c) { //c - offset
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
		recv_seq = (short) (seq-1);

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
	
	public void startAltHold() {
		queueMsg(15,1);
		altHold = true;
	}
	
	public void exitAltHold() {
		queueMsg(15,0);
		altHold = false;
	}
	
	public void toggleMode() {
		flyMode = 1-flyMode;
		queueMsg(3,flyMode);
	}

	public void reset() {
		queueMsg(0,1);
	}
	
	public void setLog(int v) {
		queueMsg(2,v);
	}
	
	public void flogsave() {
		queueMsg(0,4);
	}
	
	public void incAltitude() {
		queueMsg(16,50);
	}
	
	public void decAltitude() {
		queueMsg(16,-50);
	}
	
	public void testFailsafe() {
		queueMsg(25,0);
	}
	
	public void setController(boolean hasController) {
		this.hasController = hasController;
		if (!hasController) {
			databuffer = new byte[MSG_SIZE];
			databbuffer = ByteBuffer.wrap(databuffer);
		}
	}
	
}
