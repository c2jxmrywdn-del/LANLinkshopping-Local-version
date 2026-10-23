import { NextRequest, NextResponse } from "next/server";
import { getRun } from "workflow/api";
import { corsHeaders } from "../../../../lib/cors";

export const dynamic = "force-dynamic";

export async function OPTIONS(req: NextRequest) {
  return new NextResponse(null, { status: 204, headers: corsHeaders(req.headers.get("origin")) });
}

export async function GET(req: NextRequest, ctx: { params: Promise<{ runId: string }> }) {
  const origin = req.headers.get("origin");
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
