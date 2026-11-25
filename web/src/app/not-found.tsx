import Link from "next/link";

export default function NotFound() {
  return (
    <main className="mx-auto max-w-xl px-6 py-20">
      <h1 className="text-2xl font-semibold">Không tìm thấy trang</h1>
      <Link className="mt-6 inline-block text-primary" href="/">
        Về trang chủ
      </Link>
    </main>
  );
}
