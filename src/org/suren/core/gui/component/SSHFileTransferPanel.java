package org.suren.core.gui.component;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragSource;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.List;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.TransferHandler;
import javax.swing.filechooser.FileSystemView;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;

import org.suren.SuRenProperties;
import org.suren.core.gui.Body;
import org.suren.core.gui.common.FileViewPopup;
import org.suren.core.gui.common.MissingComponentException;
import org.suren.core.gui.common.TransferPopup;
import org.suren.core.net.Sftp;
import org.suren.core.os.UnixFile;
import org.suren.util.common.NumberUtil;
import org.suren.util.io.FileUtil;
import org.suren.util.swing.JTableUtil;

import sun.awt.shell.Win32ShellFolderManager2;

import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

public class SSHFileTransferPanel extends JPanel
{

	/**
	 * 
	 */
	private static final long		serialVersionUID	= -2481555545547485829L;
	private static final String		NAME				= "SSHFileTransfer";
	private static final String[]	LOCAL				= { "LocalName", "Size", "Type", "Modified" };
	private static final String[]	REMOTE				= { "RemoteName", "Size", "Attributes",
			"Modified"									};
	public static final String[]	TRANSFER			= { "Type", "Source File",
			"Source Directory", "Destination Directory", "Size", "Stats", "Speed", "Time" };
	private static final String[]	action				= { "Connect", "Disconnect" };

	private boolean					inited				= false;
	private File					localCurrent;
	private JTable					localTable;
	private JComboBox				localPath;
	private UnixFile				remoteCurrent;
	private JTable					remoteTable;
	private JComboBox				remotePath;
	private FileSystemView			view				= FileSystemView.getFileSystemView();
	private SimpleDateFormat		format				= new SimpleDateFormat(
																"yyyy/MM/dd hh:mm:ss");
	private Desktop					desktop;
	private Sftp					sftp;
	private Session					session;
	private JTable					transfer;
	private JLabel					status;

	public SSHFileTransferPanel() {
		this.setName(NAME);

		regListener();
	}

	private void regListener()
	{
		this.addComponentListener(new ComponentAdapter() {

			public void componentShown(ComponentEvent e)
			{
				try
				{
					init();
				}
				catch (MissingComponentException e1)
				{
					e1.printStackTrace();
				}
				Body.showing = NAME;
			}

		});
	}

	private void init() throws MissingComponentException
	{
		if (inited) return;
		inited = true;

		if (Desktop.isDesktopSupported()) desktop = Desktop.getDesktop();

		this.setLayout(new BorderLayout());

		this.add(fileCenter(), BorderLayout.CENTER);
		this.add(status("Status:"), BorderLayout.SOUTH);

		this.updateUI();
	}

	/**
	 * TODO 在下方显示目前的状态以及消息
	 * @return
	 */
	private JLabel status(String ... msg)
	{
		if(status == null)
		{
			status = new JLabel();
		}
		
		status.setText(msg[0]);

		return status;
	}

	private JComponent fileCenter() throws MissingComponentException
	{
		JSplitPane root = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
		JSplitPane filePane = new JSplitPane();

		filePane.setLeftComponent(localFile());
		filePane.setRightComponent(remoteFile());

		root.setLeftComponent(filePane);
		root.setRightComponent(transferView());

		filePane.setResizeWeight(0.5);
		root.setResizeWeight(0.8);

		return root;
	}

	private JComponent transferView() throws MissingComponentException
	{
		JScrollPane scrollPane = new JScrollPane();

		DefaultTableModel model = getModel(false);
		model.setColumnCount(TRANSFER.length);
		transfer = new JTable(model);
		transfer.setAutoCreateRowSorter(true);

		new TransferPopup(transfer);

		TableColumnModel colModel = transfer.getColumnModel();
		for (int i = 0; i < TRANSFER.length; i++)
		{
			colModel.getColumn(i).setHeaderValue(TRANSFER[i]);
		}

		scrollPane.setViewportView(transfer);

		return scrollPane;
	}

	/**
	 * 左侧栏，显示本地目录
	 * @return
	 * @throws MissingComponentException
	 */
	private JPanel localFile() throws MissingComponentException
	{
		JPanel root = new JPanel();
		root.setLayout(new BorderLayout());

		JToolBar toolBar = new JToolBar();
		JButton home = new JButton("Home");
		JButton up = new JButton("Up");
		JButton refresh = new JButton("Refresh");
		localPath = new JComboBox();
		localPath.setEditable(true);

		toolBar.add(home);
		toolBar.add(up);
		toolBar.add(refresh);
		toolBar.add(localPath);

		home.addActionListener(new ActionListener() {

			public void actionPerformed(ActionEvent e)
			{
				fillList(localTable, view.getHomeDirectory());
			}
		});

		up.addActionListener(new ActionListener() {

			public void actionPerformed(ActionEvent e)
			{
				fillList(localTable, view.getParentDirectory(localCurrent));
			}
		});

		localPath.addActionListener(new ActionListener() {

			public void actionPerformed(ActionEvent e)
			{
				if ("comboBoxEdited".equals(e.getActionCommand()))
				{
					JComboBox path = (JComboBox) e.getSource();
					fillList(localTable, new File(path.getSelectedItem().toString()));
				}
			}
		});

		refresh.addActionListener(new ActionListener() {

			public void actionPerformed(ActionEvent e)
			{
				fillList(localTable, localCurrent);
			}

		});

		DefaultTableModel model = getModel(false);
		model.setColumnCount(LOCAL.length);
		localTable = new JTable(model);
		localTable.setAutoCreateRowSorter(true);
		localTable.setDragEnabled(true);
		localTable.setName(LOCAL[0]);
		new FileViewPopup(localTable, this);
		JScrollPane scrollPane = new JScrollPane(localTable);

		fillList(localTable, view.getHomeDirectory());

		TableColumnModel columnModel = localTable.getColumnModel();
		for (int i = 0; i < LOCAL.length; i++)
		{
			columnModel.getColumn(i).setHeaderValue(LOCAL[i]);
		}

		columnModel.getColumn(0).setCellRenderer(new TableCellRenderer() {

			public Component getTableCellRendererComponent(JTable table, Object value,
					boolean isSelected, boolean hasFocus, int row, int column)
			{
				if (value instanceof File)
				{
					File file = (File) value;
					FileSystemView view = FileSystemView.getFileSystemView();
					JLabel lable = new JLabel(view.getSystemDisplayName(file), view
							.getSystemIcon(file), JLabel.LEFT);

					return lable;
				}

				return value != null ? new JLabel(value.toString()) : new JLabel();
			}
		});

		//处理鼠标事件
		localTable.addMouseListener(new MouseAdapter() {

			public void mouseClicked(MouseEvent e)
			{
				int count = e.getClickCount();

				if (count == 2)
				{
					JTable table = (JTable) e.getSource();

					int row = table.getSelectedRow();

					File file = (File) table.getValueAt(row, 0);
					
					if (file.isDirectory())
					{
						fillList(table, file);
					}
					else if (file.isFile())
					{
						try
						{
							if (!FileUtil.open(file.getPath()) && desktop != null) desktop.open(file);
						}
						catch (IOException e1)
						{
							e1.printStackTrace();
						}
					}
				}
			}
		});

		DragSource dragSource = DragSource.getDefaultDragSource();
		dragSource.createDefaultDragGestureRecognizer(localTable, DnDConstants.ACTION_COPY,
				new DragGestureListener() {

					public void dragGestureRecognized(final DragGestureEvent e)
					{
						e.startDrag(DragSource.DefaultCopyDrop, new Transferable() {

							@Override
							public boolean isDataFlavorSupported(DataFlavor flavor)
							{
								return flavor == DataFlavor.stringFlavor
										|| flavor == DataFlavor.javaFileListFlavor;
							}

							@Override
							public DataFlavor[] getTransferDataFlavors()
							{
								DataFlavor[] data = { DataFlavor.stringFlavor,
										DataFlavor.javaFileListFlavor };

								return data;
							}

							@Override
							public Object getTransferData(DataFlavor flavor)
									throws UnsupportedFlavorException, IOException
							{

								JTable table = (JTable) e.getComponent();

								return JTableUtil.getValue(table, LOCAL[0]);
							}
						});
					}
				});

		localTable.setTransferHandler(new TransferHandler() {

			/**
			 * 
			 */
			private static final long	serialVersionUID	= -4739408313189946944L;

			public boolean canImport(TransferSupport support)
			{
				if (!support.isDrop()) return false;

				if (!support.isDataFlavorSupported(DataFlavor.stringFlavor)) return false;

				return true;
			}

			public boolean importData(TransferSupport support)
			{

				if (!canImport(support)) return false;

				File file = (File) JTableUtil.getValue(localTable, LOCAL[0]);
				if (file.isFile())
				{
					file = file.getParentFile();
				}

				try
				{
					Object obj = support.getTransferable().getTransferData(DataFlavor.stringFlavor);

					if (obj instanceof UnixFile)
					{
						UnixFile unix = (UnixFile) obj;

						sftp.get(unix, file.getAbsolutePath(), transfer);
					}
				}
				catch (UnsupportedFlavorException e)
				{
					e.printStackTrace();
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}

				return true;
			}
		});

		root.add(toolBar, BorderLayout.NORTH);
		root.add(scrollPane, BorderLayout.CENTER);

		return root;
	}

	/**
	 * SSH远程文件列表
	 * @return
	 * @throws MissingComponentException
	 */
	private JPanel remoteFile() throws MissingComponentException
	{
		JPanel root = new JPanel();
		root.setLayout(new BorderLayout());

		JToolBar toolBar = new JToolBar();
		final JButton home = new JButton("Home");
		final JButton up = new JButton("Up");
		final JButton refresh = new JButton("Refresh");
		JButton connect = new JButton(action[0]);
		remotePath = new JComboBox();

		home.setEnabled(false);
		up.setEnabled(false);
		refresh.setEnabled(false);

		/*
		 * TODO 这里要现实从配置文件中读取的ip地址，
		 * 而且每次成功登录的ip地址都会加到配置文件中
		 */
		remotePath.addItem("root@192.168.10.223");
		remotePath.setEditable(true);

		toolBar.add(home);
		toolBar.add(up);
		toolBar.add(refresh);
		toolBar.add(connect);
		toolBar.add(remotePath);

		home.addActionListener(new ActionListener() {

			public void actionPerformed(ActionEvent e)
			{
				if (sftp == null) return;
				String path = sftp.getHome();

				path = path != null ? path : "/home";

				fillList(remoteTable, new UnixFile(path));
			}
		});

		up.addActionListener(new ActionListener() {

			public void actionPerformed(ActionEvent e)
			{
				fillList(remoteTable, new UnixFile(".."));
			}
		});

		remotePath.addActionListener(new ActionListener() {

			public void actionPerformed(ActionEvent e)
			{
				if ("comboBoxEdited".equals(e.getActionCommand()))
				{
					JComboBox path = (JComboBox) e.getSource();
					fillList(remoteTable, new UnixFile(path.getSelectedItem().toString()));
				}
			}
		});

		refresh.addActionListener(new ActionListener() {

			public void actionPerformed(ActionEvent e)
			{
				fillList(remoteTable, new UnixFile(sftp.pwd()));
			}
		});

		//处理ssh连接事件
		connect.addActionListener(new ActionListener() {

			public void actionPerformed(final ActionEvent e)
			{
				String cmd = e.getActionCommand();
				JButton connect = (JButton) e.getSource();

				if (action[0].equals(cmd))
				{
					String hostStr = remotePath.getSelectedItem().toString();
					String[] hostArr = (hostStr == null ? null : hostStr.split("@"));

					if (hostArr == null || hostArr.length != 2)
					{
						status("地址格式不正确");
						//TODO 提示地址格式不正确
						return;
					}

					sftp = new Sftp();
					
					try
					{
						session = sftp.open(hostArr[0], hostArr[1]);

						connect.setText(action[1]);
						remoteTable.requestFocus();

						fillList(remoteTable, new UnixFile("/"));

						home.setEnabled(true);
						up.setEnabled(true);
						refresh.setEnabled(true);
					}
					catch (JSchException e1)
					{
						status(e1.getMessage());
						e1.printStackTrace();
					}
					finally
					{
					}
				}
				else if (action[1].equals(cmd))
				{
					if (JOptionPane.OK_OPTION != JOptionPane.showConfirmDialog(Body.rootPanel,
							"Sure to disconnect?", "Warning", JOptionPane.OK_CANCEL_OPTION))
						return;

					session.disconnect();
					connect.setText(action[0]);
					remotePath.requestFocus();

					home.setEnabled(false);
					up.setEnabled(false);
					refresh.setEnabled(false);

					((DefaultTableModel) remoteTable.getModel()).setRowCount(0);
				}
			}

		});

		DefaultTableModel model = getModel(false);
		model.setColumnCount(REMOTE.length);
		remoteTable = new JTable(model);
		remoteTable.setAutoCreateRowSorter(true);
		remoteTable.setDragEnabled(true);
		remoteTable.setName(REMOTE[0]);
		new FileViewPopup(remoteTable, this);
		JScrollPane scroll = new JScrollPane(remoteTable);

		DragSource dragSource = DragSource.getDefaultDragSource();
		dragSource.createDefaultDragGestureRecognizer(remoteTable, DnDConstants.ACTION_COPY,
				new DragGestureListener() {

					public void dragGestureRecognized(final DragGestureEvent e)
					{
						e.startDrag(DragSource.DefaultCopyDrop, new Transferable() {

							@Override
							public boolean isDataFlavorSupported(DataFlavor flavor)
							{
								return flavor == DataFlavor.stringFlavor
										|| flavor == DataFlavor.javaFileListFlavor;
							}

							@Override
							public DataFlavor[] getTransferDataFlavors()
							{
								DataFlavor[] data = { DataFlavor.stringFlavor,
										DataFlavor.javaFileListFlavor };

								return data;
							}

							@Override
							public Object getTransferData(DataFlavor flavor)
									throws UnsupportedFlavorException, IOException
							{

								JTable table = (JTable) e.getComponent();
								return JTableUtil.getValue(table, REMOTE[0]);
							}
						});
					}
				});

		TableColumnModel columnModel = remoteTable.getColumnModel();
		for (int i = 0; i < REMOTE.length; i++)
		{
			columnModel.getColumn(i).setHeaderValue(REMOTE[i]);
		}

		remoteTable.addMouseListener(new MouseAdapter() {

			public void mouseClicked(MouseEvent e)
			{
				int count = e.getClickCount();

				if (count == 2)
				{
					JTable table = (JTable) e.getSource();

					int row = table.getSelectedRow();

					// the table is not enabled
					if (row == -1) return;

					UnixFile file = (UnixFile) table.getValueAt(row, 0);

					if (file.isDir())
					{
						fillList(table, new UnixFile(file.getRealPath()));
					}
					else
					{
						if (desktop != null) try
						{
							desktop.open(sftp.getFile(file.getRealPath()));
						}
						catch (IOException e1)
						{
							e1.printStackTrace();
						}
					}
				}
			}
		});

		remoteTable.setTransferHandler(new TransferHandler() {

			/**
			 * 
			 */
			private static final long	serialVersionUID	= 4544227373294972967L;

			public boolean canImport(TransferSupport support)
			{
				if (!support.isDrop()) return false;

				if (!support.isDataFlavorSupported(DataFlavor.stringFlavor)) return false;

				return true;
			}

			public boolean importData(TransferSupport support)
			{

				if (!canImport(support)) return false;

				try
				{
					Object obj = support.getTransferable().getTransferData(DataFlavor.stringFlavor);

					if (!(obj instanceof File)) return false;

					File file = (File) obj;

					sftp.put(file, sftp.pwd(), transfer);
				}
				catch (UnsupportedFlavorException e)
				{
					e.printStackTrace();
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}

				return true;
			}

			public Icon getVisualRepresentation(Transferable t)
			{
				return new ImageIcon(new SuRenProperties().getSuRen().getTitle());
			}

		});

		root.add(toolBar, BorderLayout.NORTH);
		root.add(scroll, BorderLayout.CENTER);

		return root;
	}

	/**
	 * 向table中填充内容
	 * @param table
	 * @param file
	 */
	private void fillList(JTable table, File file)
	{
		if (file == null) return;

		DefaultTableModel tableModel = (DefaultTableModel) table.getModel();

		boolean unix = file instanceof UnixFile;

		if (unix)
		{
			if (sftp == null) return;
			UnixFile unixFile = (UnixFile) file;
			sftp.cd(unixFile.getPath(), null);
			remoteCurrent = new UnixFile(sftp.pwd());
			List<UnixFile> fileList = sftp.list(remoteCurrent.getPath());

			if (fileList == null) return;

			remotePath.addItem(unixFile);
			remotePath.setSelectedIndex(remotePath.getItemCount() - 1);

			tableModel.setRowCount(fileList.size());

			for (int i = 0; i < fileList.size(); i++)
			{
				tableModel.setValueAt(fileList.get(i), i, 0);
				tableModel.setValueAt(NumberUtil.bitToFit(fileList.get(i).getSize()), i, 1);
				tableModel.setValueAt(fileList.get(i).getPermissionsStr(), i, 2);
				tableModel.setValueAt(fileList.get(i).getmTimeStr(), i, 3);
			}
		}
		else if (file.exists())
		{
			File[] fileList = view.getFiles(file, true);

			localCurrent = file;
			localPath.addItem(file);
			localPath.setSelectedIndex(localPath.getItemCount() - 1);

			tableModel.setRowCount(fileList.length);

			for (int i = 0; i < fileList.length; i++)
			{
				tableModel.setValueAt(fileList[i], i, 0);
				tableModel.setValueAt(
						NumberUtil.bitToFit(fileList[i].isFile() ? fileList[i].length() : null), i,
						1);
				tableModel.setValueAt(view.getSystemTypeDescription(fileList[i]), i, 2);
				tableModel.setValueAt(
						format.format(new java.util.Date(fileList[i].lastModified())), i, 3);
			}

			JTableUtil.columnFit(localTable);
		}
	}

	public SSHFileTransferPanel refreshLocal()
	{
		fillList(localTable, localCurrent);

		return this;
	}

	public SSHFileTransferPanel refreshRemote()
	{
		if (session.isConnected()) fillList(remoteTable, remoteCurrent);

		return this;
	}

	private DefaultTableModel getModel(final boolean editable)
	{
		return new DefaultTableModel() {
			private static final long	serialVersionUID	= 1L;

			public boolean isCellEditable(int row, int column)
			{
				return editable;
			}
		};
	}
}
