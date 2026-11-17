import { NextRequest, NextResponse } from "next/server";
import { getRun } from "workflow/api";
import { corsHeaders } from "../../../../lib/cors";
import { authorizeImport } from "../../../../lib/auth";

export const dynamic = "force-dynamic";

export async function OPTIONS(req: NextRequest) {
  return new NextResponse(null, {
    status: 204,
    headers: {
      ...corsHeaders(req.headers.get("origin")),
      "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Import-Token",
    },
  });
}

export async function GET(req: NextRequest, ctx: { params: Promise<{ runId: string }> }) {
  const origin = req.headers.get("origin");
  // 运行结果含商品标题/失败原因，与启动接口同等敏感（审计 P1-4）
  const denied = authorizeImport(req.headers);
  if (denied) {
    return NextResponse.json(
      { error: denied.error },
      { status: denied.status, headers: corsHeaders(origin) },
    );
  }
  const { runId } = await ctx.params;
  const run = getRun(runId);
  const status = await run.status;

  const body: Record<string, unknown> = { runId, status };
  if (status === "completed") {
    body.result = await run.returnValue;
  } else if (status === "failed") {
    body.error = "工作流执行失败";
  }
  return NextResponse.json(body, { headers: corsHeaders(origin) });
}
