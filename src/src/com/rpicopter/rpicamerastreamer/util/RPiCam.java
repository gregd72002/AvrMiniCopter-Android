package com.rpicopter.rpicamerastreamer.util;

import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;

import com.rpicopter.rpicamerastreamer.Callback;

import android.util.Log;

public class RPiCam {
	public String error;
	public int status = 0;
	private DataOutputStream out;
	private Callback context;
	private Timer timer;
	private byte [] buffer;
	private ByteBuffer byte_buffer;
	private DatagramPacket packet;
	private DatagramSocket socket;
	
	public RPiCam(Callback c, byte []rpi_ip, int rpi_port, byte []my_ip, int my_port,int stream_type) {
		context = c;
		try {
			status = 0;
			timer = new Timer();
			InetAddress rpi = InetAddress.getByAddress(rpi_ip);
			
			buffer = new byte[10];
			byte_buffer = ByteBuffer.wrap(buffer);
			byte_buffer.put((byte)0); //type offset 0
			byte_buffer.put(my_ip); //ip offset 1
			byte_buffer.putInt(my_port); //port offset 5
			byte_buffer.put((byte)stream_type);
			//byte_buffer.putInt(counter); //port offset 9

		    packet = new DatagramPacket(
		                buffer, buffer.length, rpi, rpi_port
		                );
		    socket = new DatagramSocket();
		    socket.setSoTimeout(500);
			
		        
		} catch (Exception ex) {
			error = ex.toString();
			status = -1;
			context.notify(0, error);
		}
	}
	
	private void ping() {
		ByteBuffer b = null;
		try {
	        socket.send(packet);
		} catch (Exception ex) {
			error = ex.toString();
			status = -1;
			context.notify(0, error);
		}
	}
	
	public void start() {
		byte_buffer.put(0,(byte)0); //type offset 0
		timer = new Timer();
		timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
            	ping();
            }
        }, 0, 1000);//put here time 1000 milliseconds=1 second
	}
	
	private void _stop() {
		try {
			Log.d("RPiCam","RPiCam stopping");
			byte_buffer.put(0,(byte)1); //type offset 0
			socket.send(packet);
		} catch (Exception ex) {
		}
	}
	
	public void stop() {
		timer.cancel();
		new Thread(new Runnable(){
		    @Override
		    public void run() {
		    	_stop();
		    }
		}).start();
	}

}
