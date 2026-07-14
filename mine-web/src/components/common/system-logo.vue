<template>
  <div class="app-logo">
    <svg
      width="100%"
      height="100%"
      viewBox="0 0 128 96"
      fill="none"
      role="img"
      aria-label="智慧矿山数据平台"
      xmlns="http://www.w3.org/2000/svg"
    >
      <defs>
        <linearGradient id="mine-logo-mountain" x1="12" y1="72" x2="106" y2="18" gradientUnits="userSpaceOnUse">
          <stop stop-color="#165DFF" />
          <stop offset="1" stop-color="#20D9F7" />
        </linearGradient>
        <linearGradient id="mine-logo-data" x1="10" y1="78" x2="118" y2="78" gradientUnits="userSpaceOnUse">
          <stop stop-color="#1565FF" />
          <stop offset="0.72" stop-color="#22D3EE" />
          <stop offset="1" stop-color="#F6B93B" />
        </linearGradient>
      </defs>

      <path
        d="M8 67 30 42l16 17"
        stroke="url(#mine-logo-mountain)"
        stroke-width="8"
        stroke-linecap="square"
        stroke-linejoin="miter"
      />
      <path
        d="m36 43 27-30 47 52"
        stroke="url(#mine-logo-mountain)"
        stroke-width="8"
        stroke-linecap="square"
        stroke-linejoin="miter"
      />
      <path d="m47 60 18-18 19 20Z" fill="url(#mine-logo-mountain)" />

      <path d="M8 73c28-17 69-16 112-2" stroke="url(#mine-logo-data)" stroke-width="3.2" stroke-linecap="round" />
      <path d="M10 81c28-16 62-16 92-5" stroke="url(#mine-logo-mountain)" stroke-width="2.8" stroke-linecap="round" />
      <path d="M19 88c23-13 48-13 68-6" stroke="url(#mine-logo-mountain)" stroke-width="2.4" stroke-linecap="round" />

      <circle cx="56" cy="64.5" r="4.5" fill="#20CFF0" />
      <circle cx="69" cy="76.5" r="4" fill="#18BDF0" />
      <circle cx="88" cy="82" r="4.5" fill="#19B9F2" />
      <circle cx="120" cy="71" r="5" fill="#F6B93B" />
    </svg>
  </div>
</template>

<style scoped>
.app-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
</style>
