package com.myanimal.org.IA_service.domain.ports.out;

public interface RemoteFileDownloadPort {

    byte[] download(String fileUrl);
}
