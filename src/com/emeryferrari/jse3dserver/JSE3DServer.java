package com.emeryferrari.jse3dserver;
import java.io.*;
import java.util.*;
import com.emeryferrari.jse3d.network.*;
import com.emeryferrari.jse3d.obj.*;
import javax.net.ssl.*;
import java.security.*;
public class JSE3DServer {
	private boolean stop = false;
	private int count = 0;
	private static final JSE3DServer CLASS_OBJ = new JSE3DServer();
	private int sceneID;
	private Scene scene;
	private ArrayList<String> users = new ArrayList<String>();
	private JSE3DServer() {}
	public static void main(String[] args) {
		System.setProperty("javax.net.ssl.trustStore", "alx.store");
		System.setProperty("javax.net.ssl.keyStore", "alx.store");
		System.setProperty("javax.net.ssl.keyStorePassword", "jse3d_alex");
		for (int i = 0; i < 1000; i++) {
			CLASS_OBJ.users.add(null);
		}
		Thread.currentThread().setName("listener");
		int port = 5107;
		int sceneID = 0;
		if (args.length == 1) {
			try {
				port = Integer.parseInt(args[0]);
			} catch (NumberFormatException ex) {
				System.out.println("Incorrectly formatted port. Defaulting to 5107...");
				port = 5107;
			}
		} else if (args.length == 2) {
			try {
				port = Integer.parseInt(args[0]);
			} catch (NumberFormatException ex) {
				System.out.println("Incorrectly formatted port. Defaulting to 5107...");
				port = 5107;
			}
			try {
				sceneID = Integer.parseInt(args[1]);
			} catch (NumberFormatException ex) {
				System.out.println("Incorrectly formatted scene ID. Defaulting to 0...");
				sceneID = 0;
			}
		} else if (args.length == 0) {
			System.out.println("Defaulting port to 5107...");
			System.out.println("Defaulting scene ID to 0...");
		} else {
			printUsage();
		}
		System.out.println("Starting jse3d server on [127.0.0.1:" + port + "].");
		CLASS_OBJ.start(port, sceneID);
	}
	private static void printUsage() {
		System.out.println("Usage: java JSE3DServer port scene_id");
		System.exit(1);
	}
	public void start(int port, int sceneID) {
		File sceneFile = new File("scenes/scn" + sceneID + ".jscn");
		if (sceneFile.exists()) {
			try {
				scene = JSE3DSerializer.loadScene(sceneFile);
			} catch (IOException ex) {
				handleException(ex);
				System.out.println("Error loading " + sceneFile.getAbsolutePath() + ". Defaulting scene to empty scene.");
			} catch (ClassNotFoundException ex) {
				handleException(ex);
				System.exit(2);
			}
		} else {
			System.out.println(sceneFile.getAbsolutePath() + " was not found. Creating file...");
			try {
				sceneFile.createNewFile();
				Object3D[] objects = {};
				Scene tmp = new Scene(objects, 5.0);
				JSE3DSerializer.saveScene(tmp, sceneFile);
			} catch (IOException ex) {
				handleException(ex);
				System.exit(3);
			}
		}
		Thread consoleReader = new Thread(new ConsoleReader());
		consoleReader.setName("console-input");
		consoleReader.start();
		Thread autosave = new Thread(new Autosave());
		autosave.setName("autosave");
		autosave.start();
		SSLServerSocket server = null;
		try {
			KeyStore ks = KeyStore.getInstance("JKS");
			ks.load(new FileInputStream("alx.store"), "jse3d_alex".toCharArray());
			KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
			kmf.init(ks, "jse3d_alex".toCharArray());
			TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
			tmf.init(ks);
			SSLContext sc = SSLContext.getInstance("TLS");
			TrustManager[] trustManagers = tmf.getTrustManagers();
			sc.init(kmf.getKeyManagers(), trustManagers, null);
			SSLServerSocketFactory ssf = sc.getServerSocketFactory();
			server = (SSLServerSocket) ssf.createServerSocket(port);
			System.out.println("Server started.");
		} catch (Exception ex) {
			handleException(ex);
			System.out.println("Server was not started successfully.");
			System.exit(1);
		}
		while (!stop) {
			try {
				if (server != null) {
					SSLSocket sslSocket = (SSLSocket) server.accept();
					System.out.println("Found client at " + sslSocket.getInetAddress().getHostAddress() + ":" + sslSocket.getPort() + ". Client ID: " + count + ".");
					Thread client = new Thread(new ClientHandler(sslSocket, count));
					client.setName("client-handler-" + count);
					count++;
					client.start();
				}
			} catch (IOException ex) {
				handleException(ex);
			}
		}
	}
	public static void handleException(Exception ex) {
		if (!(ex.getMessage().equalsIgnoreCase("socket closed") || ex.getMessage().equalsIgnoreCase("connection reset"))) {
			System.out.println("  ** EXCEPTION THROWN **");
			System.out.println(String.valueOf(ex.getClass()).split(" ")[1] + " occurred in thread \"" + Thread.currentThread().getName() + "\":");
			System.out.println("Cause: " + ex.getMessage());
			System.out.println();
		}
	}
	public class ConsoleReader implements Runnable {
		public void run() {
			try {
				BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
				String line;
				while (!stop) {
					line = reader.readLine();
					if (line != null) {
						handleCommand(line);
					}
				}
				System.exit(0);
			} catch (IOException ex) {
				handleException(ex);
			}
		}
	}
	public void handleCommand(String command) {
		if (command.equalsIgnoreCase("stop")) {
			stop = true;
			System.out.println("Stopping server...");
		} else {
			System.out.println("Unknown command.");
		}
	}
	public class ClientHandler implements Runnable {
		private SSLSocket socket;
		private int clientID;
		@SuppressWarnings("unused")
		private String username;
		public ClientHandler(SSLSocket socket, int clientID) {
			this.socket = socket;
			this.clientID = clientID;
		}
		public void run() {
			String username = null;
			try {
				BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
				username = reader.readLine();
				String version = reader.readLine();
				if (!version.equals(JSE3DServerConst.API_VERSION)) {
					socket.close();
					System.out.println("[Server thread: " + Thread.currentThread().getName() + "]: Rejected incoming user " + username + " with client ID " + clientID + " as the client version is not equal to the server version.");
				}
				if (users.contains(username)) {
					oos.writeObject("username-taken");
					oos.flush();
					System.out.println("[Server thread: " + Thread.currentThread().getName() + "]: Rejected incoming user " + username + " with client ID " + clientID + " as the username is already taken.");
					socket.close();
				} else if (username.equals("")) {
					oos.writeObject("username-invalid");
					oos.flush();
					System.out.println("[Server thread: " + Thread.currentThread().getName() + "]: Rejected incoming user with client ID " + clientID + " as the username is not valid.");
					socket.close();
				} else {
					users.set(clientID, username);
				}
				System.out.print("[Server thread: " + Thread.currentThread().getName() + "]: Uploading scene to user " + username + "... ");
				oos.writeObject(scene);
				oos.flush();
				System.out.println("Done!");
				Thread receiver = new Thread(new Receiver(socket, clientID, username));
				receiver.setName("client-receiver-" + clientID);
				receiver.start();
				Thread monitor = new Thread(new SceneMonitor(socket));
				monitor.setName("client-monitor-" + clientID);
				monitor.start();
				while (!socket.isClosed()) {
					try {
						Thread.sleep(17);
					} catch (InterruptedException ex) {
						handleException(ex);
					}
				}
				oos.flush();
				socket.close();
				if (!(username == null)) {
					users.set(clientID, null);
				}
				System.out.println("[Server thread: "+Thread.currentThread().getName() + "]: Client with client ID " + clientID + " has disconnected.");
			} catch (IOException ex) {
				System.out.println("[Server thread: "+Thread.currentThread().getName() + "]: Client with client ID " + clientID + " has disconnected.");
				if (!(username == null)) {
					users.set(clientID, null);
				}
			}
		}
	}
	public class SceneMonitor implements Runnable {
		private SSLSocket socket;
		public SceneMonitor(SSLSocket socket) {
			this.socket = socket;
		}
		public void run() {
			boolean shouldRun1 = true;
			if (socket.isClosed()) {
				shouldRun1 = false;
			}
			if (shouldRun1) {
				ObjectOutputStream oos = null;
				try {
					oos = new ObjectOutputStream(socket.getOutputStream());
				} catch (IOException ex) {
					handleException(ex);
				}
				if (oos != null) {
					boolean shouldRun2 = true;
					while (!stop && shouldRun2) {
						if (socket.isClosed()) {
							shouldRun2 = false;
						} else {
							try {
								Scene temp = scene;
								while (temp.equals(scene)) {
									try {
										Thread.sleep(17);
									} catch (InterruptedException ex) {
										handleException(ex);
									}
								}
								oos.writeObject(scene);
								oos.flush();
							} catch (IOException ex) {
								handleException(ex);
							}
						}
					}
				}
			}
		}
	}
	public class Receiver implements Runnable {
		private SSLSocket socket;
		private int clientID;
		public Receiver(SSLSocket socket, int clientID, String username) {
			this.socket = socket;
			this.clientID = clientID;
		}
		public void run() {
			boolean shouldRun = true;
			while (!stop && shouldRun) {
				if (socket.isClosed()) {
					shouldRun = false;
				} else {
					try {
						ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
						Object object;
						while ((object = ois.readObject()) != null) {
							if (object instanceof Scene) {
								System.out.println("[Server thread: " + Thread.currentThread().getName() + "]: Client with client ID " + clientID + " changed the current scene.");
								scene = (Scene) object;
							} else if (object instanceof Disconnect) {
								socket.close();
							}
						}
					} catch (IOException ex) {
						handleException(ex);
						try {
							socket.close();
						} catch (IOException ex2) {
							handleException(ex2);
						}
					} catch (ClassNotFoundException ex) {
						handleException(ex);
						System.exit(2);
					}
				}
			}
		}
	}
	public class Autosave implements Runnable {
		public void run() {
			while (!stop) {
				try {
					JSE3DSerializer.saveScene(new File("scenes/scn" +sceneID + ".jscn"), scene);
				} catch (IOException ex) {
					handleException(ex);
				}
				try {
					Thread.sleep(JSE3DServerConst.AUTOSAVE_INTERVAL);
				} catch (InterruptedException ex) {
					handleException(ex);
				}
			}
		}
	}
}