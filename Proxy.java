/*
(Sorry for my poor English first)

1. What made me develop this program?
That's just because I wanted to be able to access Google and some other websites which are blocked in China. 
While a friend of mine told me an HTTP proxy (IP + port) on the Internet, I couldn't use it from my office computer 
because my office computer (and all my colleagues' computers) had to connect to an HTTP proxy provided by my company first to 
connect to the Internet. It seemed that I needed to specify TWO proxies in Internet Options of my browser. But that was impossible,
which made me decide to write this Java program.

2. How does program work?
In a nutshell,  it is just to tell the proxy at my company to connect to the proxy provided by my friend.

The following figure illustrates how my browser connects to an HTTP server like Google
 (The figure is a bit too wide. you may need to drag the horizonal scroll bar on the bottom to see more):

my browser <---> this Java program <----> proxy at my company (Parent Proxy) <----> proxy on the Internet provided by my friend (Grandparent Proxy) <---> Google/Twitter/Youtube...
     A                    B                             C                                                             D                                             E		


B is listening on TCP port 4444 ( 4444 is a number that is hard for you to forget, isn't it? ).
On Internet Options of my Windows, set 127.0.0.1:4444 as LAN proxy of my computer.

Each time B accpets a TCP connection from A, it will create a new thread, called UpstreamThread.
In the new thread, B will establish a TCP to C and then send a CONNECT (not GET or POST) http request to C, asking C to connect to D.
The CONNECT request might consist of several lines, including "CONNECT....", "Host....", "Proxy-Connection....", and "User-Agent...", 
ending with a blank line.
   Note: I chose these lines and sent them to C just because after intercepting and analyzing
some packets to and from C, I found my browser would send these kinds of lines if it connected directly to C.
So I'm not sure this is always acceptable to a proxy.

When C is connected to D successfully, it will send an http response to B, which normally is just one LINE of text:
"HTTP/1.0 200 CONNECTION established\r\n\r\n"
I don't know why C says HTTP 1.0 rather than HTTP 1.1 in response. No need to worry. It seems to work as well.
  Note: This program doesn't check the content of the http response to the CONNECT request. I'm rather lazy.

After that, what B needs to do is just forward whatever it receives from A to C and forward whatever it receives from C to A, 
without having to analyzing the TCP data.
To prevent the streams in the two directions from blocking each other, a new thread is created, called DownstreamThread.
As you would expect, the two threads are responsible for different tasks:
(1) UpstreamThread   receives some bytes from A, and then sends them to C. 
   This will repeat forever until no more data is available or some error ocurrs.
(2) DownstreamThread receives some bytes from C, and then sends them to A. 
   This will repeat forever until no more data is available or some error ocurrs.
Actually, C will work the same way as B at the same time, just forwarding the TCP streams between B and D.

An example might help you understand the foregoing procedure better:
When the browser tries to access https://www.google.com, the following actions will happen:
(1) A connects to B on 127.0.0.1:4444
(2) B connects to C
(3) B sends "CONNECT ...." request to C, asking C to connect to D
(4) C connects to D
(5) C sends "HTTP/1.0 200 CONNECTION established\r\n\r\n" to B as an HTTP response to the above-mentioned CONNECT request.
(6) A sends "CONNECT www.google.com:443 HTTP/1.1..." request to B. 
    Note: Actually this may happen anytime after Step 1. However, it doesn't matter, because this request will be stored in the recv
    buffer of B for the TCP connectioin between A and B until B retrieves it.
(7) B forwards the request to C, without having to analyzing it.
(8) C forwards the request to D, without having to analyzing it.
(9) D needs to analyze "CONNECT www.google.com:443 HTTP/1.1...", and then connects to www.google.com:443. 
    (It may need to use DNS to get the IP of www.google.com first)
(10) D replies to C that it has connected to Google successfully, saying "...CONNECTION established..."
(11) C forwards the response to B, without having to analyzing it.
(12) B forwards the response to A, without having to analyzing it.
(13) A reads "...CONNECTION established...", and then talks to www.google.com about establishing TLS or whatever. All the data between them
     will be forwarded by B, C and D without having to be analyzed by them.
*/



import java.net.*;
import java.io.*;

class DownstreamThread extends Thread {
	InputStream   parentIn;
	OutputStream  browserOut;
	
	DownstreamThread(InputStream   parentIn,	OutputStream  browserOut) {
		this.parentIn = parentIn;
		this.browserOut = browserOut;
	}

	public void run() {
		byte[] responseBuf = new byte[1024*16];
		int readByte;

		try {
			while ((readByte = parentIn.read(responseBuf)) != -1)
				browserOut.write(responseBuf, 0, readByte);		
			System.out.println("Downstream: while == -1");
		}
		catch(Exception e) {System.out.println(System.currentTimeMillis()%1000000 + " ms: Downstream " + e.getMessage());}
		finally {
			try{
			parentIn.close();
			browserOut.close();}catch(Exception e2) {}
		}
	}
}

class UpstreamThread extends Thread {
	Socket  browserSocket;

	UpstreamThread(Socket browserSocket) {
		this.browserSocket = browserSocket;
	}

	public void run() {
		InputStream   browserIn = null;
		OutputStream  parentOut = null;
		Socket  parentSocket = null;

		try {
			// Connect to parent proxy
			parentSocket = new Socket("10.1.1.1", 80);
			
			// Send CONNECT HTTP request, asking parent proxy to connect to grandparent proxy
			String grandparentProxy = "119.1.1.1:80";
			String httpConnectReqStr = "CONNECT " + grandparentProxy + " HTTP/1.1\r\n" + 
				                       "Host: " + grandparentProxy + "\r\n" + 
				                       "Proxy-Connection: keep-alive\r\n" + 
				                       "User-Agent: Chrome/58.0.3209.110 Mozilla/5.0\r\n" +
				                       "\r\n";
			byte[] httpConnectReqArray = httpConnectReqStr.getBytes();
			parentOut = parentSocket.getOutputStream();
			parentOut.write(httpConnectReqArray);

			// Read http response to check if parent has connected to grandparent proxy successfully.
			String httpConnectResponseStr = "HTTP/1.0 200 CONNECTION established\r\n\r\n";  // or HTTP/1.1
			byte[] httpConnectResponseArray = new byte[httpConnectResponseStr.length()];
			InputStream   parentIn = parentSocket.getInputStream();
			for (int len=0; len<httpConnectResponseArray.length;)	{				
				len = parentIn.read(httpConnectResponseArray, len, httpConnectResponseArray.length-len);
				if (len < 0)
					;  // close them
			}

			DownstreamThread  downThread = new DownstreamThread(parentIn, browserSocket.getOutputStream());	
			downThread.start();
			
			browserIn = browserSocket.getInputStream();
			byte[] bufReq = new byte[1024*8];
			int readByte;
			boolean first = true;
			while ((readByte = browserIn.read(bufReq)) != -1) {
				parentOut.write(bufReq, 0, readByte);
			}
			System.out.println("upstream: while == -1");
		}
		catch (Exception e2){
			System.out.println(System.currentTimeMillis()%1000000 + " ms: Upstream:" + e2.getMessage());
		}
		finally {
		    try {
				if (browserIn != null)
					browserIn.close();
				if (parentOut != null)
					parentOut.close();

				if (browserSocket != null)					
					browserSocket.close();
				if (parentSocket != null)
					parentSocket.close();
		    } catch (Exception e){}									
		}			
	}
}

public class Proxy{

    public static void main(String[] args) {
		try {
			ServerSocket serverSocket = new ServerSocket(4444);

			for (;;){				
				Socket browserSocket = serverSocket.accept();
				
				UpstreamThread myThread = new UpstreamThread(browserSocket);

				myThread.start();
			}
		} catch (Exception e){}			        
    }
}



