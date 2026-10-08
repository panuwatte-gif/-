import grab_worker
from grab_worker_fixed import StoreWorker as FixedStoreWorker

grab_worker.StoreWorker = FixedStoreWorker

import main

main.StoreWorker = FixedStoreWorker

if __name__ == "__main__":
    main.App().mainloop()
