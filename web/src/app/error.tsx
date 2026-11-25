"use client";

export default function ErrorPage({ reset }: { reset: () => void }) {
  return (
    <main className="mx-auto max-w-xl px-6 py-20">
      <h1 className="text-2xl font-semibold">Không thể mở trang</h1>
      <p className="mt-3">Vui lòng thử lại.</p>
      <button
        className="mt-6 rounded-lg bg-primary px-4 py-2 text-white"
        onClick={reset}
      >
        Thử lại
      </button>
    </main>
  );
}
