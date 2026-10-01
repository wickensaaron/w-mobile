import Foundation
import ComposeApp

private let backgroundDownloadStart = Notification.Name("WMediaBackgroundDownloadStart")
private let backgroundDownloadCancel = Notification.Name("WMediaBackgroundDownloadCancel")
private let backgroundDownloadPause = Notification.Name("WMediaBackgroundDownloadPause")
private let backgroundDownloadDiscard = Notification.Name("WMediaBackgroundDownloadDiscard")

private struct BackgroundDownloadRequest: Decodable {
    let id: String
    let url: String
    let fileName: String
    let headers: [String: String]
}

private struct BackgroundDownloadRecord: Codable {
    let id: String
    let fileName: String
}

/// The system owns each transfer, so it can continue while this app is suspended.
/// Only download IDs and local filenames are persisted here; URLs and headers stay in URLSession.
final class BackgroundDownloadManager: NSObject, URLSessionDownloadDelegate {
    static let shared = BackgroundDownloadManager()

    private let sessionIdentifier = "com.wplatform.mobile.background-downloads"
    private let recordsKey = "wmedia.background-downloads.tasks.v1"
    private var observers: [NSObjectProtocol] = []
    private var records: [String: BackgroundDownloadRecord] = [:]
    private var pendingStarts = Set<String>()
    private var pausingIds = Set<String>()
    private var startsWaitingForPause: [String: String] = [:]
    private var discardedFileNames = Set<String>()
    private var completedFiles: [String: (URL, Int64)] = [:]
    private var backgroundCompletion: (() -> Void)?

    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: sessionIdentifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false // The person explicitly requested this download.
        configuration.waitsForConnectivity = true
        configuration.allowsCellularAccess = true
        configuration.allowsExpensiveNetworkAccess = true
        configuration.allowsConstrainedNetworkAccess = true
        configuration.timeoutIntervalForRequest = 60
        configuration.timeoutIntervalForResource = 24 * 60 * 60
        return URLSession(configuration: configuration, delegate: self, delegateQueue: .main)
    }()

    private override init() {
        super.init()
        if let data = UserDefaults.standard.data(forKey: recordsKey),
           let saved = try? JSONDecoder().decode([String: BackgroundDownloadRecord].self, from: data) {
            records = saved
        }
    }

    func start() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: backgroundDownloadStart, object: nil, queue: .main) { [weak self] note in
            self?.startDownload(note.object as? String)
        })
        observers.append(center.addObserver(forName: backgroundDownloadCancel, object: nil, queue: .main) { [weak self] note in
            self?.cancelDownload(note.object as? String)
        })
        observers.append(center.addObserver(forName: backgroundDownloadPause, object: nil, queue: .main) { [weak self] note in
            self?.pauseDownload(note.object as? String)
        })
        observers.append(center.addObserver(forName: backgroundDownloadDiscard, object: nil, queue: .main) { [weak self] note in
            self?.discardResumeData(note.object as? String)
        })
        // Reassociate with tasks that the system kept running after process termination.
        _ = session
    }

    func handleBackgroundEvents(identifier: String, completionHandler: @escaping () -> Void) {
        guard identifier == sessionIdentifier else {
            completionHandler()
            return
        }
        start()
        backgroundCompletion = completionHandler
    }

    private func startDownload(_ payload: String?) {
        guard let payload,
              let data = payload.data(using: .utf8),
              let request = try? JSONDecoder().decode(BackgroundDownloadRequest.self, from: data) else {
            return
        }
        guard !request.id.isEmpty,
              !request.fileName.isEmpty,
              request.fileName == (request.fileName as NSString).lastPathComponent,
              let url = URL(string: request.url),
              ["http", "https"].contains(url.scheme?.lowercased() ?? "") else {
            DispatchQueue.main.async {
                IosBackgroundDownloadsBridgeKt.iosBackgroundDownloadFailed(id: request.id)
            }
            return
        }
        if pausingIds.contains(request.id) {
            startsWaitingForPause[request.id] = payload
            return
        }
        guard pendingStarts.insert(request.id).inserted else { return }
        session.getAllTasks { [weak self] tasks in
            DispatchQueue.main.async {
                guard let self else { return }
                // Pause/cancel can arrive before the asynchronous task lookup returns.
                guard self.pendingStarts.contains(request.id) else { return }
                self.pendingStarts.remove(request.id)
                if let existing = tasks.first(where: {
                    self.records[String($0.taskIdentifier)]?.id == request.id &&
                    $0.state != .canceling
                }) {
                    self.sendProgress(for: existing, id: request.id)
                    return
                }
                if let directory = try? self.downloadsDirectory() {
                    let destination = directory.appendingPathComponent(request.fileName, isDirectory: false)
                    if let size = try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                       size > 0 {
                        if let resumeURL = self.resumeDataURL(for: request.fileName) {
                            try? FileManager.default.removeItem(at: resumeURL)
                        }
                        IosBackgroundDownloadsBridgeKt.iosBackgroundDownloadFinished(
                            id: request.id, localFileUri: destination.absoluteString, totalBytes: Int64(size)
                        )
                        return
                    }
                }
                var urlRequest = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
                urlRequest.httpMethod = "GET"
                urlRequest.timeoutInterval = 60
                for (name, value) in request.headers where !["range", "if-range", "accept-encoding"].contains(name.lowercased()) {
                    urlRequest.setValue(value, forHTTPHeaderField: name)
                }
                urlRequest.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
                let resumeURL = self.resumeDataURL(for: request.fileName)
                let resumeData = resumeURL.flatMap { try? Data(contentsOf: $0) }
                let task = resumeData.flatMap { $0.isEmpty ? nil : self.session.downloadTask(withResumeData: $0) }
                    ?? self.session.downloadTask(with: urlRequest)
                task.taskDescription = request.id
                self.records[String(task.taskIdentifier)] = BackgroundDownloadRecord(
                    id: request.id, fileName: request.fileName
                )
                self.saveRecords()
                task.resume()
                if let resumeURL { try? FileManager.default.removeItem(at: resumeURL) }
            }
        }
    }

    private func cancelDownload(_ id: String?) {
        guard let id, !id.isEmpty else { return }
        pendingStarts.remove(id)
        pausingIds.remove(id)
        startsWaitingForPause.removeValue(forKey: id)
        let matching = records.filter { $0.value.id == id }
        let identifiers = matching.map(\.key)
        identifiers.forEach { records.removeValue(forKey: $0) }
        saveRecords()
        for (_, record) in matching {
            if let directory = try? downloadsDirectory() {
                let destination = directory.appendingPathComponent(record.fileName, isDirectory: false)
                try? FileManager.default.removeItem(at: destination)
            }
        }
        session.getAllTasks { tasks in
            tasks.filter { $0.taskDescription == id || identifiers.contains(String($0.taskIdentifier)) }
                .forEach { $0.cancel() }
        }
    }

    private func pauseDownload(_ id: String?) {
        guard let id, !id.isEmpty else { return }
        pendingStarts.remove(id)
        pausingIds.insert(id)
        let matching = records.filter { $0.value.id == id }
        let identifiers = matching.map(\.key)
        session.getAllTasks { [weak self] tasks in
            DispatchQueue.main.async {
                guard let self else { return }
                let active = tasks.filter { identifiers.contains(String($0.taskIdentifier)) || $0.taskDescription == id }
                if active.isEmpty {
                    identifiers.forEach { self.records.removeValue(forKey: $0) }
                    self.saveRecords()
                    self.finishPause(id)
                    return
                }
                // Remove ownership before cancellation so its completion cannot fail a
                // newly resumed transfer using the same download ID.
                active.forEach { self.records.removeValue(forKey: String($0.taskIdentifier)) }
                self.saveRecords()
                for task in active {
                    guard let downloadTask = task as? URLSessionDownloadTask,
                          let record = matching[String(task.taskIdentifier)] else {
                        task.cancel()
                        self.finishPause(id)
                        continue
                    }
                    downloadTask.cancel { [weak self] data in
                        DispatchQueue.main.async {
                            guard let self else { return }
                            if let data, !data.isEmpty,
                               !self.discardedFileNames.contains(record.fileName),
                               let url = self.resumeDataURL(for: record.fileName) {
                                try? data.write(to: url, options: .atomic)
                            }
                            self.finishPause(id)
                        }
                    }
                }
            }
        }
    }

    private func finishPause(_ id: String) {
        guard pausingIds.remove(id) != nil else { return }
        if let payload = startsWaitingForPause.removeValue(forKey: id) {
            startDownload(payload)
        }
    }

    private func discardResumeData(_ fileName: String?) {
        guard let fileName,
              !fileName.isEmpty,
              fileName == (fileName as NSString).lastPathComponent else { return }
        discardedFileNames.insert(fileName)
        if let url = resumeDataURL(for: fileName) {
            try? FileManager.default.removeItem(at: url)
        }
    }

    private func resumeDataURL(for fileName: String) -> URL? {
        guard let directory = try? downloadsDirectory() else { return nil }
        return directory.appendingPathComponent(fileName + ".resume", isDirectory: false)
    }

    private func saveRecords() {
        if let data = try? JSONEncoder().encode(records) {
            UserDefaults.standard.set(data, forKey: recordsKey)
        }
    }

    private func sendProgress(for task: URLSessionTask, id: String) {
        IosBackgroundDownloadsBridgeKt.iosBackgroundDownloadProgress(
            id: id,
            downloadedBytes: task.countOfBytesReceived,
            totalBytes: task.countOfBytesExpectedToReceive
        )
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard let id = records[String(downloadTask.taskIdentifier)]?.id else { return }
        IosBackgroundDownloadsBridgeKt.iosBackgroundDownloadProgress(
            id: id,
            downloadedBytes: totalBytesWritten,
            totalBytes: totalBytesExpectedToWrite
        )
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        let key = String(downloadTask.taskIdentifier)
        guard let record = records[key],
              let response = downloadTask.response as? HTTPURLResponse,
              [200, 206].contains(response.statusCode),
              isMediaContentType(response.value(forHTTPHeaderField: "Content-Type")) else {
            return
        }
        do {
            let directory = try downloadsDirectory()
            let destination = directory.appendingPathComponent(record.fileName, isDirectory: false)
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            // URLSession removes its temporary file as soon as this delegate returns.
            try FileManager.default.moveItem(at: location, to: destination)
            let size = Int64((try destination.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0)
            guard size > 0 else {
                try? FileManager.default.removeItem(at: destination)
                return
            }
            completedFiles[key] = (destination, size)
            if let resumeURL = resumeDataURL(for: record.fileName) {
                try? FileManager.default.removeItem(at: resumeURL)
            }
        } catch {
            // The task completion callback marks this download as failed.
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let key = String(task.taskIdentifier)
        guard let record = records.removeValue(forKey: key) else { return }
        saveRecords()
        if error == nil, let completed = completedFiles.removeValue(forKey: key) {
            IosBackgroundDownloadsBridgeKt.iosBackgroundDownloadFinished(
                id: record.id,
                localFileUri: completed.0.absoluteString,
                totalBytes: completed.1
            )
        } else {
            completedFiles.removeValue(forKey: key)
            IosBackgroundDownloadsBridgeKt.iosBackgroundDownloadFailed(id: record.id)
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        let completion = backgroundCompletion
        backgroundCompletion = nil
        DispatchQueue.main.async { completion?() }
    }

    private func downloadsDirectory() throws -> URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let directory = documents.appendingPathComponent("nuvio_downloads", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var excludedDirectory = directory
        try? excludedDirectory.setResourceValue(true, forKey: .isExcludedFromBackupKey)
        return directory
    }

    private func isMediaContentType(_ raw: String?) -> Bool {
        guard let raw, !raw.isEmpty else { return true }
        let type = raw.split(separator: ";", maxSplits: 1).first.map(String.init)?.lowercased() ?? ""
        return type.hasPrefix("video/") || type.hasPrefix("audio/") || [
            "application/octet-stream", "application/mp4", "application/x-matroska",
            "application/ogg", "application/vnd.ms-asf", "application/mp2t",
            "application/x-mpeg-ts", "application/x-msvideo", "application/x-flv"
        ].contains(type)
    }
}
