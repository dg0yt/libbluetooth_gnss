package com.clearevo.libecodroidbluetooth;

public interface readline_callbacks {
    //for readline mode
    public void on_readline(byte[] readline);

    public void on_readline_stream_connected();
    public void on_readline_stream_closed();
}
