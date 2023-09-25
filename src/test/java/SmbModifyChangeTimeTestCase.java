

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.util.EnumSet;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msdtyp.FileTime;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.msfscc.fileinformation.FileBasicInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.protocol.commons.EnumWithValue;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

public class SmbModifyChangeTimeTestCase {
  
  private static final String SMB_USER = "example1";
  private static final String SMB_PASSWORD = "badpass";
  private static final String SMB_DOMAIN = "workgroup";

  
  public static final DockerImageName SAMBA_IMAGE = DockerImageName.parse("dperson/samba");

  @ClassRule
  public static GenericContainer<?> samba = new GenericContainer<>(SAMBA_IMAGE)
      .withExposedPorts(445)
      .withEnv("PERMISSIONS", "yes")
      .withEnv("USER", SMB_USER + ";" + SMB_PASSWORD)
      .withEnv("SHARE", "public;/share")
      .withEnv("SHARE2", "users;/srv;yes;no;no;" + SMB_USER)
      .withPrivilegedMode(true);
  
  private SMBClient smbClient;
  private Session session;
  private DiskShare diskShare;
  /**
   * Connects a client to the smb server
   */
  @Before
  public void doBefore() throws Exception {
    smbClient = new SMBClient();

    Connection connection = smbClient.connect("localhost", samba.getMappedPort(445));
    
    AuthenticationContext ac =
        new AuthenticationContext(SMB_USER, SMB_PASSWORD.toCharArray(), SMB_DOMAIN);

    session = connection.authenticate(ac);

    diskShare = (DiskShare) session.connectShare("users");
        
  }

  /**
   * Clean up and disconnects the client
   */
  @After
  public void doAfter() throws Exception {
    List<FileIdBothDirectoryInformation> files = diskShare.list("/");
    files
      .stream()
      .filter(file -> !isVirtualDirectory(file.getFileName()))
      .forEach(file -> delete(file));
    try {
      if (diskShare.isConnected()) {
        diskShare.close();
        session.logoff();
      }
    } finally {
      smbClient.close();
    }
  }

  private void delete(FileIdBothDirectoryInformation file) {
    if (EnumWithValue.EnumUtils.isSet(file.getFileAttributes(), FileAttributes.FILE_ATTRIBUTE_DIRECTORY)) {
      diskShare.rmdir(file.getFileName(), true);
    }
    else {
      diskShare.rm(file.getFileName());
    }
  }
  
  private boolean isVirtualDirectory(String fileName) {
    return ".".equals(fileName) || "..".equals(fileName);
  }

  private void putFile(String path, Object content) throws Exception {
    File file = (File) getDiskEntryWrite(path);
    OutputStream os = file.getOutputStream();
    if (content instanceof String) {
      IOUtils.write((String) content, os, StandardCharsets.UTF_8);
    } else if (content instanceof byte[]) {
      IOUtils.write((byte[]) content, os);
    }
    os.flush();
    os.close();
    file.flush();
    file.close();
  }
  
  private DiskEntry getDiskEntryWrite(String path) throws FileSystemException {
    return diskShare.open(path,
                          EnumSet.of(AccessMask.MAXIMUM_ALLOWED),
                          EnumSet.of(com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_NORMAL),
                          EnumSet.of(SMB2ShareAccess.FILE_SHARE_WRITE),
                          SMB2CreateDisposition.FILE_OPEN_IF,
                          EnumSet.of(SMB2CreateOptions.FILE_NO_COMPRESSION));
  }
  
  @Test
  public void modifyChangeTime() throws Exception {
    String path = "test.txt";
    putFile(path, "Test!");
    FileAllInformation fileInfo = diskShare.getFileInformation(path);
    FileBasicInformation beforeFbi = fileInfo.getBasicInformation();
    FileTime newChangeTime = FileTime.ofEpochMillis(beforeFbi.getChangeTime().toEpochMillis() + 2000);
    FileBasicInformation newFbi = new FileBasicInformation(FileBasicInformation.DONT_UPDATE,
        FileBasicInformation.DONT_UPDATE, 
        FileBasicInformation.DONT_UPDATE,
        newChangeTime,
        beforeFbi.getFileAttributes());
    
    assertEquals("newChangeTime -> ChangeTime", newChangeTime, newFbi.getChangeTime());
    assertNotEquals("newChangeTime -> LastWriteTime", newChangeTime, newFbi.getLastWriteTime());
    
    diskShare.setFileInformation(path, newFbi);

    fileInfo = diskShare.getFileInformation(path);
    
    assertEquals("ChangeTime", newChangeTime, fileInfo.getBasicInformation().getChangeTime());
    assertEquals("LastWriteTime", newChangeTime, fileInfo.getBasicInformation().getLastWriteTime());

  }
  
  @Test
  public void modifyChangeTimeByLastWriteTime() throws Exception {
    String path = "test.txt";
    putFile(path, "Test!");
    FileAllInformation fileInfo = diskShare.getFileInformation(path);
    FileBasicInformation beforeFbi = fileInfo.getBasicInformation();
    FileTime newChangeTime = FileTime.ofEpochMillis(beforeFbi.getChangeTime().toEpochMillis() + 2000);
    FileBasicInformation newFbi = new FileBasicInformation(FileBasicInformation.DONT_UPDATE,
        FileBasicInformation.DONT_UPDATE, 
        newChangeTime,
        FileBasicInformation.DONT_UPDATE,
        beforeFbi.getFileAttributes());
    
    assertNotEquals("newChangeTime -> ChangeTime", newChangeTime, newFbi.getChangeTime());
    assertEquals("newChangeTime -> LastWriteTime", newChangeTime, newFbi.getLastWriteTime());
    
    diskShare.setFileInformation(path, newFbi);

    fileInfo = diskShare.getFileInformation(path);
    
    assertEquals("ChangeTime", newChangeTime, fileInfo.getBasicInformation().getChangeTime());
    assertEquals("LastWriteTime", newChangeTime, fileInfo.getBasicInformation().getLastWriteTime());

  }

}
