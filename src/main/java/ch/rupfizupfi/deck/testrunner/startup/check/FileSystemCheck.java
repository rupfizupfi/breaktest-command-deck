package ch.rupfizupfi.deck.testrunner.startup.check;

import ch.rupfizupfi.deck.filesystem.StorageLocationService;

public class FileSystemCheck extends AbstractCheck {
    public StorageLocationService storageLocationService;

    public FileSystemCheck(StorageLocationService storageLocationService) {
        this.storageLocationService = storageLocationService;
    }


    @Override
    public void execute() throws CheckFailedException {
        var resultDataLocation = storageLocationService.getResultDataLocation();
        if (resultDataLocation == null) {
            throw new CheckFailedException("Result data location is not set");
        }

        if(!resultDataLocation.toFile().isDirectory()) {
            throw new CheckFailedException("Result data location is not a directory");
        }

        if (!resultDataLocation.toFile().canWrite()) {
            throw new CheckFailedException("Cannot write to result data location");
        }
    }
}
