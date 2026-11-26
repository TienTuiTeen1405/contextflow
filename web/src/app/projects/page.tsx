import Link from "next/link";

export default function ProjectsPage() {
  return (
    <main className="mx-auto max-w-4xl px-6 py-16">
      <Link href="/" className="text-sm text-primary">
        ← ContextFlow
      </Link>
      <h1 className="mt-8 text-3xl font-semibold">Dự án của bạn</h1>
      <div className="mt-8 rounded-2xl border border-dashed border-border bg-white p-10">
        <h2 className="font-semibold">Không gian dự án đang được xây dựng</h2>
        <p className="mt-2 text-slate-600">
          Danh sách và thao tác tạo dự án sẽ xuất hiện khi hoàn tất giao diện
          quản lý dự án.
        </p>
      </div>
    </main>
  );
}
