import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class TFTPServer {
    public static final int TFTPPORT = 4970;
    public static final int BUFSIZE = 516;
    public static final String READDIR = "C:/read/"; //custom address at your PC
    public static final String WRITEDIR = "C:/write/"; //custom address at your PC

    // OP codes
    public static final int OP_RRQ = 1;
    public static final int OP_WRQ = 2;
    public static final int OP_DAT = 3;
    public static final int OP_ACK = 4;
    public static final int OP_ERR = 5;

    public static void main(String[] args) throws IOException {
        if (args.length > 0)
        {
            System.err.printf("usage: java %s\n", TFTPServer.class.getCanonicalName());
            System.exit(1);
        }
        //Starting the server
        try
        {
            TFTPServer server= new TFTPServer();
            server.start();
        }
        catch (SocketException e)
        {e.printStackTrace();}
    }

    private void start() throws IOException {
        byte[] buf= new byte[BUFSIZE];

        // Create socket
        DatagramSocket socket = new DatagramSocket(null);

        // Create local bind point
        SocketAddress localBindPoint= new InetSocketAddress(TFTPPORT);
        socket.bind(localBindPoint);

        System.out.printf("Listening at port %d for new requests\n", TFTPPORT);

        // Loop to handle client requests
        while (true)
        {

            final InetSocketAddress clientAddress = receiveFrom(socket, buf);

            // If clientAddress is null, an error occurred in receiveFrom()
            if (clientAddress == null)
                continue;

            final StringBuffer requestedFile= new StringBuffer();
            final int reqtype = ParseRQ(buf, requestedFile);


            new Thread()
            {
                public void run()
                {
                    try
                    {
                        DatagramSocket sendSocket= new DatagramSocket(0);

                        // Connect to client
                        sendSocket.connect(clientAddress);

                        System.out.printf("%s request for %s from %s using port %d\n",
                                (reqtype == OP_RRQ)?"Read":"Write", requestedFile,
                                clientAddress.getHostName(), clientAddress.getPort());

                        // Read request
                        if (reqtype == OP_RRQ)
                        {
                            requestedFile.insert(0, READDIR);
                            HandleRQ(sendSocket, requestedFile.toString(), OP_RRQ);
                        }
                        // Write request
                        else
                        {
                            requestedFile.insert(0, WRITEDIR);
                            HandleRQ(sendSocket,requestedFile.toString(),OP_WRQ);
                        }
                        sendSocket.close();
                    }
                    catch (SocketException e) {
                        e.printStackTrace();
                    }

                    catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }.start();
        }
    }

    /**
     * Reads the first block of data, i.e., the request for an action (read or write).
     * @param socket (socket to read from)
     * @param buf (where to store the read data)
     * @return socketAddress (the socket address of the client)
     */
    private InetSocketAddress receiveFrom(DatagramSocket socket, byte[] buf) throws IOException {
        // Create datagram packet
        DatagramPacket receivePacket = new DatagramPacket(buf, buf.length);

        // Receive packet
        socket.receive(receivePacket);

        // Get client address and port from the packet
        InetSocketAddress socketAddress = new InetSocketAddress(receivePacket.getAddress(),receivePacket.getPort());

        return socketAddress;
    }

    /**
     * Parses the request in buf to retrieve the type of request and requestedFile
     *
     * @param buf (received request)
     * @param requestedFile (name of file to read/write)
     * @return opcode (request type: RRQ or WRQ)
     */
    private int ParseRQ(byte[] buf, StringBuffer requestedFile) {
        // See "TFTP Formats" in TFTP specification for the RRQ/WRQ request contents
        StringBuilder stringBuilder = new StringBuilder();

        for (int i = 2; i < buf.length; i++) {

            if ((char) buf[i] == '\0') {
                break;
            } else {
                stringBuilder.append((char) buf[i]);
            }
        }

        requestedFile.append(stringBuilder.toString());
        ByteBuffer wrap = ByteBuffer.wrap(buf);
        int opcode = wrap.getShort();

        return opcode;
    }

    /**
     * Handles RRQ and WRQ requests
     *
     * @param sendSocket (socket used to send/receive packets)
     * @param requestedFile (name of file to read/write)
     * @param opcode (RRQ or WRQ)
     */
    private void HandleRQ(DatagramSocket sendSocket, String requestedFile, int opcode) throws IOException {
        if(opcode == OP_RRQ) {
            // See "TFTP Formats" in TFTP specification for the DATA and ACK packet contents
            boolean result = send_DATA_receive_ACK(requestedFile, opcode, sendSocket);
        }

        else if (opcode == OP_WRQ) {
            boolean result = receive_DATA_send_ACK(requestedFile, opcode, sendSocket);
        }

    }

    /**
     To be implemented
     */
    private boolean send_DATA_receive_ACK(String file, int block, DatagramSocket socket) {
        boolean check = false;

        try {
            String[] split = file.split("\0");
            File fileName = new File(split[0]);

            FileInputStream input = new FileInputStream(fileName);

            while (true) {
                byte[] buf = new byte[512];
                int byteReader = input.read(buf);

                if (byteReader < 512)
                    check = true;

                ByteBuffer buffedData = ByteBuffer.allocate(516);

                buffedData.putShort((short) OP_DAT);
                buffedData.putShort((short) block);
                buffedData.put(buf);

                try {
                    //send packet
                    DatagramPacket sendPacket = new DatagramPacket(buffedData.array(), byteReader + 4);
                    socket.send(sendPacket);

                    //receive ACK
                    ByteBuffer bufferedAck = ByteBuffer.allocate(OP_ACK);
                    DatagramPacket receiveAck = new DatagramPacket(bufferedAck.array(), bufferedAck.array().length);
                    socket.receive(receiveAck);


                    ByteBuffer buffer = ByteBuffer.wrap(receiveAck.getData());
                    short opcode = buffer.getShort();
                    short ackBlock = buffer.getShort();

                    if (opcode == OP_ERR) {
                        send_ERR(socket,"Error packet sent", 0);
                        break;
                    }

                    if (ackBlock == block)
                        block++;

                    if (check)
                        break;

                }

                catch (SocketTimeoutException e) {
                    System.out.println("Socket timeout");
                }
            }

        }

        catch (IOException e) {
            e.printStackTrace();
        }
        return true;
    }

    private boolean receive_DATA_send_ACK(String file, int block, DatagramSocket socket) {

        boolean check = false;

        String[] split = file.split("\0");
        File fileName = new File(split[0]);

        try {
            FileOutputStream output = new FileOutputStream(fileName);

            ByteBuffer bufferedAck = ByteBuffer.allocate(OP_ACK);
            bufferedAck.putShort((short) OP_ACK);
            bufferedAck.putShort((short) block);

            DatagramPacket packet = new DatagramPacket(bufferedAck.array(), bufferedAck.array().length);  //Sending ack
            socket.send(packet);

            while (true) {
                byte[] data = new byte[BUFSIZE];
                DatagramPacket dataReceive = new DatagramPacket(data, data.length);
                socket.receive(dataReceive);

                if (dataReceive.getData().length < 512)
                    check = true;


                ByteBuffer buffer = ByteBuffer.wrap(data);
                short opCode = buffer.getShort();

                if (opCode == OP_DAT) {
                    output.write(Arrays.copyOfRange(dataReceive.getData(), 4, dataReceive.getLength()));
                    ByteBuffer sendAck = ByteBuffer.allocate(OP_ACK);

                    sendAck.putShort((short) OP_ACK);
                    sendAck.putShort(buffer.getShort());

                    DatagramPacket acknowledgment = new DatagramPacket(sendAck.array(), sendAck.array().length);
                    socket.send(acknowledgment);
                }

                if (check) {
                    break;
                }
            }

            output.flush();
            output.close();
        }

        catch (IOException e) {
            e.printStackTrace();
        }


        return true;
    }

    private void send_ERR(DatagramSocket socket, String error, int errorNumber) throws IOException {
        ByteBuffer errorBuf = ByteBuffer.allocate(error.length() + OP_ERR);
        errorBuf.putShort((short) OP_ERR);
        errorBuf.putShort((short) errorNumber);
        errorBuf.put(error.getBytes());

        DatagramPacket sendError = new DatagramPacket(errorBuf.array(), errorBuf.array().length);

        socket.send(sendError);
    }
}
