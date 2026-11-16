/** A tiny promise pool: at most `max` tasks run at once (background prefetches must not flood the API). */
export function createLimiter(max: number) {
  let active = 0
  const queue: (() => void)[] = []
  const next = () => {
    if (active >= max) return
    const start = queue.shift()
    if (start) start()
  }
  return function run<T>(task: () => Promise<T>): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      queue.push(() => {
        active += 1
        task().then(resolve, reject).finally(() => {
          active -= 1
          next()
        })
      })
      next()
    })
  }
}
