import Link from "next/link";
import { ArrowRight, GitBranch, Library, ShieldCheck } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";

export default function HomePage() {
  return (
    <main className="mx-auto flex min-h-screen max-w-5xl flex-col justify-center px-6 py-16">
      <span className="mb-6 text-sm font-semibold tracking-widest text-primary">
        CONTEXTFLOW
      </span>
      <h1 className="max-w-3xl text-4xl font-semibold leading-tight sm:text-6xl">
        Giữ mạch công việc.
        <br />
        Kết nối tri thức dự án.
      </h1>
      <p className="mt-6 max-w-xl text-lg leading-8 text-slate-600">
        Không gian cho hội thoại chính, các nhánh khảo sát và những quyết định
        đã được xác nhận.
      </p>
      <div className="mt-8">
        <Link className={buttonVariants()} href="/projects">
          Mở không gian dự án <ArrowRight size={18} />
        </Link>
      </div>
      <div className="mt-16 grid gap-4 sm:grid-cols-3">
        {[
          {
            Icon: GitBranch,
            title: "Hội thoại hai tầng",
            text: "Tách khảo sát phụ và duy trì mạch chính.",
          },
          {
            Icon: Library,
            title: "Tri thức dùng chung",
            text: "Dùng lại kết quả đã chọn trong cùng dự án.",
          },
          {
            Icon: ShieldCheck,
            title: "Quyết định rõ ràng",
            text: "Theo dõi quyết định hiện hành và lịch sử thay thế.",
          },
        ].map(({ Icon, title, text }) => (
          <article
            key={title}
            className="rounded-2xl border border-border bg-white p-6"
          >
            <Icon className="mb-4 text-primary" size={24} />
            <h2 className="font-semibold">{title}</h2>
            <p className="mt-2 text-sm leading-6 text-slate-600">{text}</p>
          </article>
        ))}
      </div>
      <p className="mt-8 text-sm text-slate-500">
        Bản khởi tạo. Chức năng tài khoản, hội thoại và tri thức đang được phát
        triển.
      </p>
    </main>
  );
}
