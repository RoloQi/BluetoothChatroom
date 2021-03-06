package com.example.rolo.bluetoothchatroom;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import java.io.*;
import java.util.ArrayList;
import java.util.UUID;

public class TaskService extends Service {
    public static final String MY_TAG = "RoloSong";
    private static final String UUID_STRING = "8ce255c0-200a-11e0-ac64-0800200c9a66";
    @Override
    public void onCreate() {

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if(bluetoothAdapter == null){
            return;
        }
        taskThread = new TaskThread();
        taskThread.start();
        super.onCreate();
    }

    public TaskService() {
    }
    private boolean serving = false;
    private static ArrayList<Task> taskQueue = new ArrayList<>();
    private TaskThread taskThread;
    private BluetoothAdapter bluetoothAdapter;
    private AcceptThread acceptThread;
    private ConnectThread connectThread;
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    private static Handler handler_ActivityMain;

    private Handler handler_TaskService = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what){
                case Task.GET_REMOTE_STATE:
                    Message activityMsg = handler_ActivityMain.obtainMessage();
                    activityMsg.what = msg.what;
                    if(acceptThread != null && acceptThread.isAlive())
                        activityMsg.obj = "accepting";
                    else if(connectedThread!=null && connectedThread.isAlive())
                        activityMsg.obj = "Device ";//+connectedThread.getRomoteName()
                    else if(connectThread != null && connectThread.isAlive())
                        activityMsg.obj = "connecting "; //connectingThread.getDevice().getName()
                    else{
                        activityMsg.obj = "Restart accepting process";
                        acceptThread = new AcceptThread();
                        acceptThread.start();
                        serving = true;
                    }
                    handler_ActivityMain.sendMessage(activityMsg);
                    break;
            }
            super.handleMessage(msg);
        }
    };

    /**
     * called when start service from outer component
     * @param c environment variable
     * @param handler handler to update User Interface
     */
    public static void start(Context c, Handler handler){
        handler_ActivityMain = handler;
        //start service explicitly
        Intent intent = new Intent(c,TaskService.class);
        c.startService(intent);
    }

    //close service
    public static void stop(Context c){
        Intent intent = new Intent(c, TaskService.class);
        c.stopService(intent);
    }

    //commit task to taskservice
    public static void newTask(Task task){
        synchronized (taskQueue){
        taskQueue.add(task);
        }
    }
    //synchronized(taskQueue){ taskQueue.add(task); }

    private class TaskThread extends Thread{
        private Boolean isRun = true;
        private int count = 0;

        //Terminates the current Thread
        public void cancel(){
            isRun = false;
        }

        @Override
        public void run() {

            Task task;
            while (isRun){
                if(taskQueue.size() > 0){
                    synchronized (taskQueue){
                        task = taskQueue.get(0);
                        accomplishTask(task);
                    }
                }else{
                    try{
                        Thread.sleep(200);
                        count++;
                    }catch(InterruptedException e){}
                    if(count >= 50){
                        count = 0;
                        Message handlerMsg = handler_TaskService.obtainMessage();
                        handlerMsg.what = Task.GET_REMOTE_STATE;

                        handler_TaskService.sendMessage(handlerMsg);
                    }
                }
            }
        }

    }

    private void accomplishTask(Task task){
        switch(task.getCurrentTaskID()){
            case Task.START_ACCEPT:
                //accept client as a server
                acceptThread = new AcceptThread();
                acceptThread.start();
                serving = true;
                break;
            case Task.CONNECT_THREAD:

                if(task.parameters == null)
                    break;
                BluetoothDevice pair = (BluetoothDevice)task.parameters[0];
                connectThread = new ConnectThread(pair);
                connectThread.start();
                serving = false;
                break;
            case Task.SEND_MSG:
                boolean sucess = false;
                    /* TODO
                    commThread

                    * */
                connectedThread.write((String) task.parameters[0]);
                break;
        }
        synchronized (taskQueue){
            taskQueue.remove(task);
        }
    }

    private class AcceptThread extends Thread{
        private BluetoothServerSocket serverSocket;
        private boolean isRun = true;

        public AcceptThread() {

            BluetoothServerSocket bluetoothServerSocket = null;
            try{
                bluetoothServerSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("BluetoothChatRoomR",
                        UUID.fromString(UUID_STRING));//UUID? question TODO
            }catch(IOException e){}
            serverSocket = bluetoothServerSocket;
        }

        @Override
        public void run() {

            BluetoothSocket bluetoothSocket = null;
            while(isRun){
                Log.d(MY_TAG,"enter accepting thread");
                try{
                    Log.d(MY_TAG, "trying Accept");
                    bluetoothSocket = serverSocket.accept();
                    Log.d(MY_TAG,"ending accept");
                }catch(IOException e){
                    Log.d(MY_TAG, "accept Thread error occured");
                    if(isRun){
                        try{
                            serverSocket.close();
                        }catch (IOException e1){
                        }
                        acceptThread = new AcceptThread();
                        acceptThread.start();
                        serving = true;
                    }
                    break;
                }
                Log.d(MY_TAG, "trying Acceptthread phase 2");
                if(bluetoothSocket != null){
                    Log.d(MY_TAG,"receive socket as receiver");
                    manageConnectedSocket(bluetoothSocket);
                    try{
                        bluetoothSocket.close();
                    }catch(IOException e){
                    }
                    acceptThread = null;
                    isRun = false;
                }
                Log.d(MY_TAG, "trying Acceptthread phase 3");
            }
        }

        public void cancel(){
            try{
                serving = false;
                serverSocket.close();
                acceptThread = null;
                if(connectThread != null && connectThread.isAlive())
                    connectThread.cancel();
            }catch(IOException e){

            }
        }
    }

    private ConnectedThread connectedThread;
    private void manageConnectedSocket(BluetoothSocket socket){
        Log.d(MY_TAG,"manage connect socket" + socket.getRemoteDevice().getName());
        connectedThread = new ConnectedThread(socket);
        connectedThread.start();
    }

    //thread that used to manage connected device
    private class ConnectedThread extends Thread{
        private final BluetoothSocket bluetoothSocket;
        private final InputStream inputStream;

        public OutputStream getOutputStream() {
            return outputStream;
        }

        private final OutputStream outputStream;
        private BufferedWriter bufferedWriter;
        public ConnectedThread(BluetoothSocket bluetoothSocket){
            Log.d(MY_TAG,"connected thread build successfull" + bluetoothSocket.getRemoteDevice().getName());
            this.bluetoothSocket = bluetoothSocket;
            InputStream bfr_input = null;
            OutputStream bfr_opt = null;
            try{
                bfr_input = bluetoothSocket.getInputStream();
                bfr_opt = bluetoothSocket.getOutputStream();
            }catch(IOException e){}
            inputStream = bfr_input;
            outputStream = bfr_opt;
            bufferedWriter = new BufferedWriter(new PrintWriter(outputStream));
        }

        @Override
        public void run() {
            write(bluetoothAdapter.getName());
            Message handlerMsg;
            String buffer;
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));

            while(true){
                try{
                    buffer = bufferedReader.readLine();
                    if(buffer == null)
                        continue;
                    if (handler_ActivityMain == null)
                        return;
                    buffer = bluetoothSocket.getRemoteDevice().getName() + " : " + buffer;
                    handlerMsg = handler_ActivityMain.obtainMessage();
                    handlerMsg.what = Task.RECEIVE_MSG;
                    handlerMsg.obj = buffer;
                    handler_ActivityMain.sendMessage(handlerMsg);
                }catch (IOException e){
                    try{
                        bluetoothSocket.close();
                    }catch(IOException e1){
                        connectedThread = null;
                    }
                    break;
                }
            }
        }

        public boolean write(String msg){
            if(msg == null) return false;
            try{
                bufferedWriter.write(msg + "\n");
                bufferedWriter.flush();

            }catch (IOException e){
                return false;
            }
            return true;
        }

        public String getRemoteName(){
            return bluetoothSocket.getRemoteDevice().getName();
        }
        public void cancel(){
            try{
                bluetoothSocket.close();
            }catch(IOException e){}
            connectedThread = null;
        }
    }

    private class ConnectThread extends Thread{
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device){
            if(acceptThread != null && acceptThread.isAlive())
                acceptThread.cancel();
            if(connectedThread != null && connectedThread.isAlive())
                connectedThread.cancel();
            BluetoothSocket temp = null;
            mmDevice = device;
            try{
                temp = device.createRfcommSocketToServiceRecord(UUID.fromString(UUID_STRING));
            }catch(IOException e){}
            mmSocket = temp;
        }

        public BluetoothDevice getDevice(){
            return mmDevice;
        }

        public void run(){
            Log.d(MY_TAG,"connectthread running");
            bluetoothAdapter.cancelDiscovery();

            try{
                Log.d(MY_TAG,"thy connect" + mmDevice.getName());
                mmSocket.connect();
            }catch(IOException e){
                Log.d(MY_TAG, "connection failed");
                try{
                    mmSocket.close();
                }catch(IOException e1){}
            }
            manageConnectedSocket(mmSocket);
        }

        public void cancel(){
            try{
                mmSocket.close();

            }catch(IOException e){}
        }
    }
}
