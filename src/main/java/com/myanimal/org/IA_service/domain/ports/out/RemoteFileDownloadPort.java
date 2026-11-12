package com.myanimal.org.IA_service.domain.ports.out;

import com.myanimal.org.IA_service.domain.model.DownloadedFile;

public interface RemoteFileDownloadPort {

    DownloadedFile download(String fileUrl);
}
