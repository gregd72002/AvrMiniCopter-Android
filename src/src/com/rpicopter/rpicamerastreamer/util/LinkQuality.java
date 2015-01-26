package com.rpicopter.rpicamerastreamer.util;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

public class LinkQuality {
	private int rpi_ip = 0;
	private int gw = 0;
	private WifiManager wm;
	private int level = 0;
	private int linkspeed = 0;
	
	public LinkQuality(Context mContext) {
		wm = (WifiManager)mContext.getSystemService(Context.WIFI_SERVICE);
		DhcpInfo dhcpInfo=wm.getDhcpInfo();
		gw = dhcpInfo.gateway;
	}
	
	public void setRpiIp(int r) {
		rpi_ip = r;
	}
	
	public int getGW() {
		return gw;
	}
	
	public void update() {
		WifiInfo info=wm.getConnectionInfo();
		level = info.getRssi();	
		linkspeed = info.getLinkSpeed();
	}

	
	public int getLinkSpeed() {
		return linkspeed;
	}
	
	public int _getLevel() {
		return level;
	}
	
	public int getLevel() {
		return level;
		//if (rpi_ip == gw) return level;
		//return 0;
	}

}
