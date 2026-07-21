import { afterEach } from 'vitest';

function createMemoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() {
      return values.size;
    },
    clear: () => values.clear(),
    getItem: key => values.get(key) ?? null,
    key: index => Array.from(values.keys())[index] ?? null,
    removeItem: key => values.delete(key),
    setItem: (key, value) => values.set(key, String(value))
  };
}

const testLocalStorage = createMemoryStorage();
const testSessionStorage = createMemoryStorage();
Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: testLocalStorage });
Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: testSessionStorage });
Object.defineProperty(window, 'localStorage', { configurable: true, value: testLocalStorage });
Object.defineProperty(window, 'sessionStorage', { configurable: true, value: testSessionStorage });

afterEach(() => {
  sessionStorage.clear();
  localStorage.clear();
});
