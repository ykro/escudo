import type { Metadata } from "next";
import { SignIn } from "@/components/portal/SignIn";

export const metadata: Metadata = { title: "Entrar" };

export default function SignInPage() {
  return <SignIn />;
}
